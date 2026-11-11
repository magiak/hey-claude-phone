package cz.magiak.asistent.meeting

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import cz.magiak.asistent.Language
import cz.magiak.asistent.Config
import cz.magiak.asistent.MainActivity
import cz.magiak.asistent.R
import cz.magiak.asistent.SpeechModel
import cz.magiak.asistent.WakeWordListener
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Meeting mode: records the whole meeting to a WAV, sends it for transcription in pieces cut at
 * pauses, and answers "Hey Claude" questions out loud from the transcript so far. It keeps the
 * mic for the whole meeting, so the dashboard's own listener stays off meanwhile.
 *
 * Threads: capture (mic, WAV, wake word, cutting) and one worker for everything that waits on the
 * network or the voice, so pieces are transcribed in order and a question waits for the piece before it.
 */
class MeetingService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))


    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "meeting-worker") }
    @Volatile private var running = false
    @Volatile private var finishing = false
    // Set while a question is being answered: the answer itself must not trigger another one.
    @Volatile private var busy = false
    @Volatile private var quietUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var beeper: ToneGenerator? = null

    private lateinit var dir: File
    private lateinit var wav: WavWriter
    private lateinit var transcript: File
    private lateinit var answerer: Answerer
    private lateinit var speaker: Speaker
    @Volatile private var transcriber: Transcriber? = null
    private var startedAt = 0L
    private var quiet = false

    // Capture thread only.
    private val levels = Levels()
    @Volatile private var recognizer: Recognizer? = null
    private var recognizerOrigin = -1L
    private var scheduledUpTo = 0L
    private var pending: Pending? = null

    // Worker thread only.
    private var transcribedUpTo = 0L
    private var recent = listOf<Word>()
    // Ends of pieces still to transcribe. After an hour offline they go out one piece at a time,
    // not as one 100 MB request, and still split at the pauses they were cut at.
    private val owed = sortedSetOf<Long>()

    private class Pending(val wakeEnd: Long, val heardAt: Long) {
        var scanned = 0
        var lastSpeech = -1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (running) requestFinish("button") else if (!finishing) stopSelf()
            return START_NOT_STICKY
        }
        if (running || finishing) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        running = true
        startedAt = Meeting.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val fake = intent?.getBooleanExtra(EXTRA_FAKE, false) == true
        val source = intent?.getStringExtra(EXTRA_SOURCE)?.let(::File)
        // A played-in recording is a test, often at night next to sleeping people.
        quiet = fake || source != null
        val speed = intent?.getFloatExtra(EXTRA_SPEED, 1f) ?: 1f

        dir = File(MeetingArchive.root(this), FOLDER.format(Date(startedAt))).apply { mkdirs() }
        transcript = File(dir, "transcript.md").apply { writeText("# ${getString(R.string.meeting_transcript_title, title())}\n\n") }
        wav = WavWriter(File(dir, "audio.wav"))
        answerer = if (fake) FakeAnswerer(this) else ClaudeAnswerer(labels = labels)
        speaker = Speaker(this, if (quiet) dir else null)
        if (!fake) transcriber = ScribeTranscriber()
        if (!quiet) beeper = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, Config.BEEP_VOLUME) }.getOrNull()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:meeting").apply { acquire() }
        Meeting.started(startedAt)
        Log.i(Config.TAG, "Meeting: started, ${dir.absolutePath}${if (fake) ", dry run" else ""}")

        SpeechModel.get(this) { result ->
            result.onSuccess { model ->
                if (!running) return@onSuccess
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), WakeWordListener.meetingGrammar()).apply { setWords(true) }
                if (fake) transcriber = VoskTranscriber(model)
            }.onFailure {
                Log.e(Config.TAG, "Meeting: speech model did not load", it)
                Meeting.note(getString(R.string.meeting_note_no_model, Config.WAKE_LABEL))
            }
        }
        speaker.announce(getString(R.string.meeting_announce_start))
        Thread({ capture(source, speed) }, "meeting").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker.shutdown()
        if (Meeting.state != Meeting.State.IDLE) Meeting.ended()
        wakeLock?.takeIf { it.isHeld }?.release()
        beeper?.release()
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    private fun capture(source: File?, speed: Float) {
        val input = source?.let { FileInput(it, speed) } ?: MicInput.open()
        if (input == null) {
            running = false
            finishing = true
            wav.close()
            Meeting.ended()
            Meeting.note(getString(R.string.meeting_note_no_mic))
            main.post { stopSelf() }
            return
        }
        val buf = ShortArray(READ_SAMPLES)
        var reads = 0
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                val before = wav.samples
                wav.write(buf, n)
                levels.add(buf, n)
                listen(buf, n, before)
                checkQuestion()
                maybeCut()
                if (++reads % HEADER_EVERY == 0) wav.patchHeader()
                if (wav.samples > MAX_MEETING_S * SAMPLE_RATE) requestFinish("three hours")
            }
        } catch (e: Exception) {
            Log.e(Config.TAG, "Meeting: recording crashed", e)
            Meeting.note(getString(R.string.meeting_note_crashed, e.message.orEmpty()))
        } finally {
            input.close()
            recognizer?.close()
            recognizer = null
            wav.close()
        }
        finish(wav.samples)
    }

    private fun listen(buf: ShortArray, n: Int, before: Long) {
        val rec = recognizer ?: return
        if (recognizerOrigin < 0) recognizerOrigin = before
        if (rec.acceptWaveForm(buf, n)) onHeard(rec.result)
    }

    private fun onHeard(json: String) {
        val heard = WakeWordListener.heard(json) ?: return
        val at = WakeWordListener.wakeAt(heard) ?: return
        val wakeEnd = recognizerOrigin + (heard[at + Config.WAKE_PHRASE.size - 1].end * SAMPLE_RATE).toLong()
        Log.i(Config.TAG, "Meeting: heard ${heard.joinToString(" ") { "${it.word} %.2f".format(it.conf) }}")
        if (busy || pending != null || finishing || wakeEnd < quietUntil) return
        if (WakeWordListener.hasCommand(heard, at)) {
            requestFinish("„${Config.WAKE_LABEL}, end the meeting“")
            return
        }
        beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        pending = Pending(wakeEnd, wav.samples)
    }

    // The question ends at the first long pause after speech; nothing said within a few seconds
    // means the wake word was a false alarm or he gave up.
    private fun checkQuestion() {
        val p = pending ?: return
        val level = levels.speechLevel()
        val first = ((p.wakeEnd + AFTER_WAKE) / FRAME).toInt()
        for (f in maxOf(p.scanned, first) until levels.size) {
            val at = f.toLong() * FRAME
            if (at >= p.heardAt && at < p.heardAt + BEEP_MASK) continue
            if (levels[f] >= level) p.lastSpeech = f
        }
        p.scanned = levels.size
        val now = levels.size
        when {
            p.lastSpeech >= 0 && now - p.lastSpeech >= QUESTION_PAUSE -> ask(p, (p.lastSpeech + 3L) * FRAME)
            p.lastSpeech < 0 && now - first >= NO_QUESTION -> {
                pending = null
                Log.i(Config.TAG, "Meeting: nothing was said after \"${Config.WAKE_LABEL}\".")
            }
            now - first >= MAX_QUESTION -> ask(p, wav.samples)
        }
    }

    private fun ask(p: Pending, end: Long) {
        pending = null
        busy = true
        val to = minOf(end, wav.samples)
        scheduledUpTo = maxOf(scheduledUpTo, to)
        worker.execute { answer(p.wakeEnd / SAMPLE_RATE.toDouble(), to) }
    }

    // A piece goes out every 2-3 min, cut in a pause so no word is split between two requests.
    private fun maybeCut() {
        if (pending != null || finishing) return
        val since = wav.samples - scheduledUpTo
        if (since < MIN_PIECE_S * SAMPLE_RATE) return
        val from = ((scheduledUpTo + MIN_PIECE_S * SAMPLE_RATE) / FRAME).toInt()
        val cut = levels.pause(from) ?: if (since >= MAX_PIECE_S * SAMPLE_RATE) levels.quietest(from) else return
        scheduledUpTo = cut
        worker.execute { piece(cut) }
    }

    private fun piece(to: Long) {
        try {
            transcribeUpTo(to)
            Meeting.note(null)
        } catch (e: Exception) {
            Log.w(Config.TAG, "Meeting: transcribing a piece failed", e)
            Meeting.note(getString(R.string.meeting_note_transcribe_failed, describe(e)))
        }
    }

    // Worker only. A failed piece stays owed, so the next call retries it before its own.
    private fun transcribeUpTo(to: Long) {
        owed.add(to)
        val t = transcriber ?: throw TranscribeFailed(R.string.meeting_scribe_not_ready)
        while (true) {
            val end = owed.firstOrNull()?.takeIf { it <= to } ?: return
            val from = transcribedUpTo
            if (end - from >= MIN_SLICE) {
                val started = System.currentTimeMillis()
                val words = t.transcribe(Wav.read(wav.file, from, end)).map { it.shifted(from / SAMPLE_RATE.toDouble()) }
                appendWords(words)
                transcribedUpTo = end
                recent = (recent + words).takeLast(RECENT_WORDS)
                Log.i(Config.TAG, "Meeting: transcribed ${(end - from) / SAMPLE_RATE} s in ${(System.currentTimeMillis() - started) / 1000} s, ${words.size} words")
            }
            owed.remove(end)
        }
    }

    private fun answer(wakeEndS: Double, to: Long) {
        try {
            transcribeUpTo(to)
            val question = questionAfterWake(wakeEndS)
            when {
                question == null -> Log.i(Config.TAG, "Meeting: \"${Config.WAKE_LABEL}\" is not in the transcript, staying silent.")
                question.isBlank() -> Log.i(Config.TAG, "Meeting: the question is empty, staying silent.")
                isEndRequest(question) -> requestFinish("„$question“")
                else -> {
                    Log.i(Config.TAG, "Meeting: question \"$question\"")
                    val reply = answerer.answer(transcript.readText(), question)
                    if (reply == null) {
                        Log.i(Config.TAG, "Meeting: Claude judged it was not a question.")
                    } else {
                        appendLine("${labels.question} $question")
                        appendLine("Claude: $reply")
                        Meeting.answered(question, reply)
                        speaker.say(reply)
                    }
                }
            }
        } catch (e: MeetingFailure) {
            speaker.say(describe(e))
        } catch (e: Exception) {
            Log.e(Config.TAG, "Meeting: answering crashed", e)
            speaker.say(getString(R.string.meeting_error_generic))
        } finally {
            quietUntil = wav.samples + SAMPLE_RATE
            busy = false
        }
    }

    // Scribe writes the wake word its own way ("Hey Claude", "Hej, Klaude"...). Requiring it near
    // where Vosk heard it is a second, independent check: Vosk alone fires on Czech talk now and then.
    private fun questionAfterWake(wakeEndS: Double): String? {
        val i = recent.indices.lastOrNull { k ->
            CLAUDE.matches(normalize(recent[k].text)) && abs(recent[k].end - wakeEndS) <= WAKE_SLACK_S
        } ?: return null
        return recent.drop(i + 1).joinToString(" ") { it.text }.trim().trimStart(',', '.', ' ')
    }

    // Short only: "when does the meeting end" is a question, not an order.
    private fun isEndRequest(question: String) =
        question.split(Regex("\\s+")).size <= END_MAX_WORDS && END_REQUEST.containsMatchIn(question)

    private fun requestFinish(why: String) {
        if (finishing) return
        finishing = true
        Log.i(Config.TAG, "Meeting: ending, $why")
        speaker.announce(getString(R.string.meeting_announce_end))
        running = false
    }

    private fun finish(total: Long) {
        Meeting.finishing()
        worker.execute {
            var problem: String? = null
            try {
                transcribeUpTo(total)
            } catch (e: Exception) {
                problem = getString(R.string.meeting_transcript_incomplete, describe(e))
            }
            val summary = try {
                if (transcript.readLines().none { it.startsWith("[") }) throw AnswerFailed(R.string.meeting_transcript_empty)
                answerer.summarize(transcript.readText())
            } catch (e: Exception) {
                Log.w(Config.TAG, "Meeting: the summary failed", e)
                problem = problem ?: describe(e)
                null
            }
            File(dir, "summary.md").writeText(
                "# ${getString(R.string.meeting_summary_title, title())}\n\n" +
                    (summary ?: getString(R.string.meeting_summary_missing, problem.orEmpty())) + "\n"
            )
            Log.i(Config.TAG, "Meeting: saved to ${dir.absolutePath}")
            speaker.say(
                when {
                    summary == null -> getString(R.string.meeting_say_summary_missing, problem.orEmpty())
                    problem != null -> getString(R.string.meeting_say_done_incomplete)
                    else -> getString(R.string.meeting_say_done)
                }
            )
            Meeting.ended()
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun appendWords(words: List<Word>) {
        if (words.isEmpty()) return
        val out = StringBuilder()
        var line: StringBuilder? = null
        var lineStart = 0.0
        var speaker: String? = null
        var last = 0.0
        for (w in words) {
            val newLine = line == null || w.speaker != speaker || w.start - lineStart > LINE_MAX_S || w.start - last > LINE_GAP_S
            if (newLine) {
                line?.let { out.append(it).append('\n') }
                line = StringBuilder("[${CLOCK.format(Date(startedAt + (w.start * 1000).toLong()))}] ${speakerLabel(w.speaker)}: ${w.text}")
                lineStart = w.start
                speaker = w.speaker
            } else {
                line!!.append(' ').append(w.text)
            }
            last = w.end
        }
        line?.let { out.append(it).append('\n') }
        transcript.appendText(out.toString())
    }

    private fun appendLine(text: String) {
        val at = startedAt + transcribedUpTo * 1000 / SAMPLE_RATE
        transcript.appendText("[${CLOCK.format(Date(at))}] $text\n")
    }

    // Scribe numbers speakers per request, so "Speaker 1" in two pieces need not be the same person.
    private fun speakerLabel(id: String?) =
        id?.substringAfterLast('_')?.toIntOrNull()?.let { getString(R.string.meeting_speaker, it + 1) } ?: getString(R.string.meeting_speech)

    private val labels by lazy { MeetingLabels.of(this) }

    private fun describe(e: Exception) = if (e is MeetingFailure) getString(e.res, *e.args) else e.message.orEmpty()

    // Device locale, read per use: the phone's language can change between meetings.
    private fun title() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedAt))

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).trim { !it.isLetter() }.removeSuffix("'s")

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.meeting_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, MeetingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(getString(R.string.meeting_notification_title))
            .setContentText(getString(R.string.meeting_notification_text, Config.WAKE_LABEL))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), getString(R.string.meeting_notification_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    /** RMS level of every 100 ms, to find pauses and the end of a question. */
    private class Levels {
        private var data = FloatArray(36_000)
        private var sum = 0.0
        private var count = 0
        private var cachedAt = -1
        private var cached = MIN_SPEECH_LEVEL
        var size = 0
            private set

        operator fun get(i: Int) = data[i]

        fun add(buf: ShortArray, n: Int) {
            for (i in 0 until n) {
                val s = buf[i].toDouble()
                sum += s * s
                if (++count == FRAME) {
                    if (size == data.size) data = data.copyOf(size * 2)
                    data[size++] = sqrt(sum / count).toFloat()
                    sum = 0.0
                    count = 0
                }
            }
        }

        // A fixed bar fails in a loud room; the quiet end of the last 30 s is the room's own floor.
        fun speechLevel(): Float {
            if (cachedAt >= 0 && size - cachedAt < 10) return cached
            val from = maxOf(0, size - FLOOR_FRAMES)
            if (size - from < 10) return cached
            val floor = data.copyOfRange(from, size).sorted()[(size - from) / 10]
            cached = maxOf(MIN_SPEECH_LEVEL, floor * 4)
            cachedAt = size
            return cached
        }

        /** Middle of the first pause of at least 0.4 s from frame [from] on, as a sample position. */
        fun pause(from: Int): Long? {
            val level = speechLevel()
            var run = 0
            for (f in from until size) {
                if (data[f] < level) {
                    if (++run == PAUSE_FRAMES) return (f - run / 2L) * FRAME
                } else {
                    run = 0
                }
            }
            return null
        }

        fun quietest(from: Int): Long {
            val f = (from until size).minByOrNull { data[it] } ?: (size - 1)
            return f.toLong() * FRAME + FRAME / 2
        }
    }

    private interface Input : Closeable {
        fun read(buf: ShortArray): Int
    }

    private class MicInput(private val recorder: AudioRecord) : Input {
        override fun read(buf: ShortArray) = recorder.read(buf, 0, buf.size)

        override fun close() {
            recorder.stop()
            recorder.release()
        }

        companion object {
            // VOICE_RECOGNITION: Claude's voice mode, opened on top, then still hears too (tried 2026-11-10).
            @SuppressLint("MissingPermission")
            fun open(): MicInput? {
                val size = maxOf(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    READ_SAMPLES * 4,
                )
                val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    return null
                }
                recorder.startRecording()
                return MicInput(recorder)
            }
        }
    }

    /** Debug: a WAV played in instead of the mic, then silence. */
    private class FileInput(file: File, private val speed: Float) : Input {
        private val stream = file.inputStream().buffered().apply { skip(Wav.HEADER.toLong()) }
        private val bytes = ByteArray(READ_SAMPLES * 2)

        override fun read(buf: ShortArray): Int {
            Thread.sleep((buf.size * 1000L / SAMPLE_RATE / speed).toLong())
            var n = 0
            while (n < bytes.size) {
                val r = stream.read(bytes, n, bytes.size - n)
                if (r < 0) break
                n += r
            }
            bytes.fill(0, n)
            for (i in buf.indices) buf[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            return buf.size
        }

        override fun close() = stream.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        // Same 0.2 s as Vosk's SpeechService: its endpointing depends on how audio arrives.
        const val READ_SAMPLES = 3_200
        private const val FRAME = 1_600
        private const val HEADER_EVERY = 50
        private const val MAX_MEETING_S = 3 * 3_600L

        private const val MIN_PIECE_S = 120L
        private const val MAX_PIECE_S = 180L
        private const val PAUSE_FRAMES = 4
        private const val MIN_SLICE = SAMPLE_RATE / 2
        // Room noise measured 10-15 on this phone, the voice-mode chime 2 500-3 000 (2026-11-10).
        private const val MIN_SPEECH_LEVEL = 80f
        private const val FLOOR_FRAMES = 300

        private const val AFTER_WAKE = SAMPLE_RATE / 5L
        private const val BEEP_MASK = SAMPLE_RATE * 6L / 10
        private const val QUESTION_PAUSE = 15
        private const val NO_QUESTION = 60
        private const val MAX_QUESTION = 300
        private const val WAKE_SLACK_S = 2.5
        private const val RECENT_WORDS = 400
        private const val END_MAX_WORDS = 6
        private const val LINE_MAX_S = 30.0
        private const val LINE_GAP_S = 2.0

        private val CLAUDE = Regex("^[ck]l(au|ou|o|ó)[dt][eéi]?$")
        private val END_REQUEST = Regex(
            """\b(end|stop|finish)\b.*\b(meeting|recording)\b|\bukon[čc]\w*\b.*\bporad\w*|\bkonec\b.*\bporad\w*|\bzastav\w*\b.*\bnahr\w*""",
            RegexOption.IGNORE_CASE,
        )

        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "meeting"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_FAKE = "dry_run"
        private const val EXTRA_SOURCE = "file"
        private const val EXTRA_SPEED = "speed"

        private val FOLDER = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT)
        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

        /**
         * [fake] runs without keys (offline English model, canned answers, voice into files),
         * [source] plays a WAV in instead of the mic, voice into files too. Both are for tests only.
         */
        fun start(context: Context, fake: Boolean = false, source: String? = null, speed: Float = 1f) {
            if (Meeting.state != Meeting.State.IDLE) return
            // Set before the service runs: the dashboard resumes in between and would reopen its listener.
            Meeting.started(System.currentTimeMillis())
            context.startForegroundService(
                Intent(context, MeetingService::class.java)
                    .putExtra(EXTRA_FAKE, fake)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_SPEED, speed)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeetingService::class.java).setAction(ACTION_STOP))
        }

        /** Debug: do the keys work? Two short calls, the outcome goes to logcat. */
        fun checkKeys(testKey: String?) {
            val scribe = runCatching {
                val t = if (testKey != null) ScribeTranscriber(testKey) else ScribeTranscriber()
                t.transcribe(Wav.header(SAMPLE_RATE.toLong()) + ByteArray(SAMPLE_RATE * 2)).size
            }
            Log.i(Config.TAG, "Meeting: transcription check: ${scribe.fold({ "works, $it words" }, { "${it.javaClass.simpleName}: ${it.message}" })}")
            val claude = runCatching {
                val a = if (testKey != null) ClaudeAnswerer(testKey) else ClaudeAnswerer()
                a.answer("[10:00:00] Speaker 1: Test.", "Say just the word works.")
            }
            Log.i(Config.TAG, "Meeting: Claude check: ${claude.fold({ "answered \"$it\"" }, { "${it.javaClass.name}: ${it.message}" })}")
        }
    }
}
