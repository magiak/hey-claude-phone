package cz.magiak.asistent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline wake word: Vosk limited to a few words, so it listens continuously, sends nothing
 * anywhere and does not beep. Holds the microphone while started; stop() must run before
 * Claude opens. Tuning lives in Config (words, decoys, confidence).
 */
class WakeWordListener(
    private val context: Context,
    private val onWake: (meeting: Boolean) -> Unit,
    private val onVoice: () -> Unit,
    private val onMiss: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onHeard: (String) -> Unit,
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var running = false
    private var retryMs = FIRST_RETRY_MS

    fun start() {
        if (running) return
        running = true
        onStatus("Načítám model řeči…")
        SpeechModel.get(context, ::onModel)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        release()
    }

    private fun release() {
        service?.stop()
        service?.shutdown()
        service = null
        recognizer?.close()
        recognizer = null
    }

    private fun onModel(result: Result<Model>) {
        result.onSuccess(::listen).onFailure { error ->
            Log.e(Config.TAG, "Model řeči se nenačetl", error)
            onStatus("Model řeči se nenačetl: ${error.message}")
            retryLater()
        }
    }

    // A start, stop and start during loading leaves two callbacks; only one may open the mic.
    private fun listen(model: Model) {
        if (!running || service != null) return
        recognizer = Recognizer(model, SAMPLE_RATE, grammar()).apply { setWords(true) }
        service = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
        onStatus("Poslouchám")
    }

    // A wall device has nobody to reopen the app, so a dead mic must heal itself.
    private fun retryLater() {
        if (!running) return
        handler.postDelayed({ SpeechModel.get(context, ::onModel) }, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun check(json: String) {
        val heard = heard(json) ?: return
        retryMs = FIRST_RETRY_MS
        val text = heard.joinToString("  ") { "${it.word} ${"%.2f".format(it.conf)}" }
        Log.i(Config.TAG, "Slyším: $text")
        onHeard(text)
        val at = wakeAt(heard)
        if (at != null) {
            stop()
            onWake(hasCommand(heard, at))
        } else if (heard.any { it.word in Config.WAKE_PHRASE.drop(1) || it.word in Config.DECOY_WORDS }) {
            // In silence Vosk returns a lone "hey 1.00" every 20 s (seen on the phone 2026-11-08),
            // so a lone first word is not a near miss; nor are the meeting words, they come up in talk.
            onMiss()
        }
    }

    override fun onPartialResult(hypothesis: String) {
        val partial = JSONObject(hypothesis).optString("partial")
        // In a quiet room the partial sits on a lone "hey" more than half the time (replay of the
        // 2026-11-08 12:35 recording), which kept the glow on for good. And an orange glow while
        // he says the phrase, then green a second later, read to him as red then green.
        if (partial.isNotBlank() && !partial.startsWith(Config.WAKE_PHRASE.first())) onVoice()
    }

    override fun onResult(hypothesis: String) = check(hypothesis)
    override fun onFinalResult(hypothesis: String) = check(hypothesis)
    override fun onTimeout() {}

    override fun onError(exception: Exception) {
        Log.e(Config.TAG, "Poslouchání spadlo", exception)
        onStatus("Mikrofon vypadl, zkouším to znovu.")
        release()
        retryLater()
    }

    /** A recognized word, times in seconds from the recognizer's start. */
    data class Heard(val word: String, val conf: Double, val start: Double, val end: Double)

    companion object {
        const val SAMPLE_RATE = 16_000f
        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L

        /** The dashboard's grammar: the wake word, and the wake word starting a meeting. */
        fun grammar() = grammarWith(Config.MEETING_START)

        /** The grammar during a meeting: the wake word, and the wake word ending it. */
        fun meetingGrammar() = grammarWith(Config.MEETING_END)

        private fun grammarWith(command: List<String>): String {
            val phrase = Config.WAKE_PHRASE.joinToString(" ")
            val phrases = listOf(phrase, "$phrase ${command.joinToString(" ")}")
            return JSONArray((phrases + Config.WAKE_PHRASE + command + Config.DECOY_WORDS + "[unk]").distinct()).toString()
        }

        /** One Vosk result's words, or null for a result without any. */
        fun heard(json: String): List<Heard>? {
            val words = JSONObject(json).optJSONArray("result") ?: return null
            return (0 until words.length()).map { i ->
                words.getJSONObject(i).let { Heard(it.getString("word"), it.getDouble("conf"), it.getDouble("start"), it.getDouble("end")) }
            }
        }

        // The rule check() applies, shared with the replay of a recording and the meeting.
        fun isWake(heard: List<Heard>) = wakeAt(heard) != null

        /** Index of the wake phrase's first word, or null. */
        fun wakeAt(heard: List<Heard>): Int? {
            val n = Config.WAKE_PHRASE.size
            return (0..heard.size - n).firstOrNull { i ->
                (0 until n).all { k ->
                    val w = heard[i + k]
                    w.word == Config.WAKE_PHRASE[k] && w.conf >= Config.MIN_CONFIDENCE &&
                        (k == 0 || w.start - heard[i + k - 1].end <= Config.MAX_WAKE_GAP_S)
                }
            }
        }

        /**
         * True when the wake phrase is followed by its command ("record the meeting" on the dashboard,
         * "end the meeting" during one), both its first and last word above the bar: Czech talk right
         * after the wake word lands on short English words now and then.
         */
        fun hasCommand(heard: List<Heard>, wakeAt: Int): Boolean {
            val after = heard.drop(wakeAt + Config.WAKE_PHRASE.size).filter { it.conf >= Config.MIN_CONFIDENCE }.map { it.word }
            val verb = after.indexOfFirst { it == Config.MEETING_START.first() || it == Config.MEETING_END.first() }
            return verb >= 0 && Config.MEETING_START.last() in after.drop(verb + 1)
        }
    }
}
