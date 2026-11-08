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
    private val onWake: () -> Unit,
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
        onStatus("Poslouchám. Řekni „${Config.WAKE_LABEL}“, nebo klepni na obrazovku.")
    }

    // A wall device has nobody to reopen the app, so a dead mic must heal itself.
    private fun retryLater() {
        if (!running) return
        handler.postDelayed({ SpeechModel.get(context, ::onModel) }, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun check(json: String) {
        val words = JSONObject(json).optJSONArray("result") ?: return
        retryMs = FIRST_RETRY_MS
        val heard = (0 until words.length()).map {
            val w = words.getJSONObject(it)
            w.getString("word") to w.getDouble("conf")
        }
        val text = heard.joinToString("  ") { (word, conf) -> "$word ${"%.2f".format(conf)}" }
        Log.i(Config.TAG, "Slyším: $text")
        onHeard(text)
        if (isWake(heard)) {
            stop()
            onWake()
        } else if (heard.any { (word, _) -> word != "[unk]" && word != Config.WAKE_PHRASE.first() }) {
            // In silence Vosk returns a lone "hey 1.00" every 20 s (seen on the phone 2026-11-08),
            // so a lone first word is not a near miss.
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

    companion object {
        const val SAMPLE_RATE = 16_000f
        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L

        fun grammar(): String {
            val phrase = Config.WAKE_PHRASE.joinToString(" ")
            return JSONArray((listOf(phrase) + Config.WAKE_PHRASE + Config.DECOY_WORDS + "[unk]").distinct()).toString()
        }

        // The rule check() applies, shared with the replay of a recording.
        fun isWake(heard: List<Pair<String, Double>>): Boolean {
            val n = Config.WAKE_PHRASE.size
            return (0..heard.size - n).any { i ->
                (0 until n).all { k -> heard[i + k].first == Config.WAKE_PHRASE[k] && heard[i + k].second >= Config.MIN_CONFIDENCE }
            }
        }
    }
}
