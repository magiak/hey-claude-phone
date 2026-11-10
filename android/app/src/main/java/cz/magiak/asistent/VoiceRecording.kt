package cz.magiak.asistent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug tool: one guided recording of his voice, so the wake word is tuned on the PC against
 * the same audio as many times as needed instead of him repeating the phrase. The mic is
 * opened exactly like Vosk's SpeechService opens it, or the tuning would not transfer.
 * Files stay in the phone's app folder until pulled over USB; nothing is sent anywhere.
 */
class VoiceRecording(
    private val context: Context,
    private val onStep: (text: String, prompt: Boolean) -> Unit,
    private val onLeft: (seconds: Int) -> Unit,
    private val onDone: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    fun start() {
        val name = "nahravka-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        Thread({
            // A crash here would take the dashboard down with it.
            runCatching { record(File(dir, "$name.wav"), File(dir, "$name.txt")) }.onFailure {
                Log.e(Config.TAG, "Nahrávání spadlo", it)
                main.post { onDone("Nahrávání spadlo: ${it.message}") }
            }
        }, "nahravka").start()
    }

    fun cancel() {
        cancelled = true
    }

    @SuppressLint("MissingPermission")
    private fun record(wav: File, marks: File) {
        val recorder = openMic()
        if (recorder == null) {
            main.post { onDone("Mikrofon se nepodařilo otevřít, nahrávání neběží.") }
            return
        }
        val steps = steps()
        var next = 0
        var samples = 0L
        var lastLeft = -1
        val chunk = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteBuffer.allocate(CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        val log = StringBuilder("# $SAMPLE_RATE Hz, mono, 16 bit; time in seconds from the start of the WAV\n")
        try {
            FileOutputStream(wav).use { out ->
                out.write(ByteArray(WAV_HEADER))
                recorder.startRecording()
                // Steps are timed by samples written, so the marks match the audio exactly.
                while (!cancelled && samples < LENGTH_S * SAMPLE_RATE) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    val t = samples.toDouble() / SAMPLE_RATE
                    while (next < steps.size && steps[next].at <= t) {
                        val step = steps[next++]
                        log.append("%s %.2f\n".format(Locale.US, step.tag, t))
                        main.post { onStep(step.text, step.tag.startsWith("near") || step.tag.startsWith("far")) }
                    }
                    bytes.clear()
                    for (i in 0 until read) bytes.putShort(chunk[i])
                    out.write(bytes.array(), 0, read * 2)
                    samples += read
                    val left = (LENGTH_S - t).toInt()
                    if (left != lastLeft) {
                        lastLeft = left
                        main.post { onLeft(left) }
                    }
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        if (cancelled) {
            wav.delete()
            return
        }
        log.append("end %.2f\n".format(Locale.US, samples.toDouble() / SAMPLE_RATE))
        writeWavHeader(wav, samples)
        marks.writeText(log.toString())
        Log.i(Config.TAG, "Nahrávka uložená: ${wav.absolutePath}")
        main.post {
            onDone("Hotovo, díky. Vyhodnocuji nahrávku…")
            replay(context, wav, onDone)
        }
    }

    // The listener has just let go of the mic; its thread is joined, but the audio HAL may lag.
    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        repeat(3) {
            val size = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                CHUNK_SAMPLES * 2,
            )
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
            Thread.sleep(300)
        }
        return null
    }

    private class Step(val at: Double, val tag: String, val text: String)

    // He said the phrase every two seconds through the whole first take (2026-11-08): neither the
    // flash nor the text told him to say it once, so each prompt now says TEĎ and then tells him to wait.
    private fun steps(): List<Step> {
        val say = "TEĎ řekni jednou\n„${Config.WAKE_LABEL}“"
        val wait = "Mlč a počkej, až zase blikne."
        return buildList {
            add(Step(0.0, "start", "Stůj blízko. Až okraje bliknou zeleně, řekni jednou „${Config.WAKE_LABEL}“."))
            for (i in 1..5) {
                val at = 3.0 + (i - 1) * PROMPT_S
                add(Step(at, "near$i", "$say\n($i z 10)"))
                add(Step(at + 2.5, "wait$i", wait))
            }
            add(Step(28.0, "move", "Teď odejdi 2 až 3 metry dál, kam budeš mluvit z gauče nebo od stolu. Počkej tam na bliknutí."))
            for (i in 6..10) {
                val at = 35.0 + (i - 6) * PROMPT_S
                add(Step(at, "far$i", "$say\n($i z 10)"))
                add(Step(at + 2.5, "wait$i", wait))
            }
            add(Step(TALK_S, "talk", "Konec. Teď půl minuty normálně mluv česky, o čemkoli, jen ne o Claudovi."))
        }
    }

    private fun writeWavHeader(wav: File, samples: Long) {
        val data = samples * 2
        val h = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        RandomAccessFile(wav, "rw").use { it.write(h.array()) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        // Same chunk as SpeechService (0.2 s): endpointing depends on how audio arrives.
        const val CHUNK_SAMPLES = 3_200
        const val PROMPT_S = 5.0
        const val TALK_S = 60.0
        const val LENGTH_S = 90
        private const val WAV_HEADER = 44

        /**
         * Feeds a recording through the phone's own recognizer with the current Config and
         * writes every result as raw JSON, so the PC script can prove it decodes the same.
         */
        fun replay(context: Context, wav: File, onDone: (String) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            SpeechModel.get(context) { result ->
                val model = result.getOrElse {
                    onDone("Model řeči se nenačetl, nahrávku nevyhodnotím.")
                    return@get
                }
                Thread({
                    val text = runCatching {
                        val (asked, talk) = decode(model, wav, File(wav.path.removeSuffix(".wav") + "-telefon.txt"))
                        "Hotovo, díky. Telefon na nahrávce poznal „${Config.WAKE_LABEL}“ ${asked}× z 10, " +
                            "při běžné řeči se spustil ${talk}×."
                    }.getOrElse {
                        Log.e(Config.TAG, "Přehrávka spadla", it)
                        "Nahrávku se nepodařilo vyhodnotit: ${it.message}"
                    }
                    main.post { onDone(text) }
                }, "prehravka").start()
            }
        }

        // Counts by where the phrase starts: before the talk part it was asked for, after it not.
        private fun decode(model: Model, wav: File, out: File): Pair<Int, Int> {
            var asked = 0
            var talk = 0
            val lines = StringBuilder()
            lines.append(JSONObject().put("grammar", WakeWordListener.grammar())
                .put("min_conf", Config.MIN_CONFIDENCE).toString()).append('\n')
            fun take(json: String) {
                val heard = WakeWordListener.heard(json) ?: return
                lines.append(json.replace("\n", "")).append('\n')
                if (WakeWordListener.isWake(heard)) {
                    if (heard[0].start < TALK_S) asked++ else talk++
                }
            }
            Recognizer(model, SAMPLE_RATE.toFloat(), WakeWordListener.grammar()).use { recognizer ->
                recognizer.setWords(true)
                wav.inputStream().buffered().use { input ->
                    input.skip(WAV_HEADER.toLong())
                    val buf = ByteArray(CHUNK_SAMPLES * 2)
                    while (true) {
                        var n = 0
                        while (n < buf.size) {
                            val r = input.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        if (n == 0) break
                        if (recognizer.acceptWaveForm(buf, n)) take(recognizer.result)
                    }
                }
                take(recognizer.finalResult)
            }
            out.writeText(lines.toString())
            Log.i(Config.TAG, "Přehrávka: ${asked}× z 10, při řeči ${talk}×, ${out.absolutePath}")
            return asked to talk
        }
    }
}
