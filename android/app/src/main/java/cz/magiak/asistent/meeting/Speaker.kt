package cz.magiak.asistent.meeting

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import cz.magiak.asistent.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Answers out loud in Czech through the phone's own voice. [quietDir] writes each sentence to a
 * WAV there instead of playing it, so a test run at night makes no sound.
 */
class Speaker(context: Context, private val quietDir: File?) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val initialized = CountDownLatch(1)
    private val speaking = ConcurrentHashMap<String, CountDownLatch>()
    private val ids = AtomicInteger()
    @Volatile private var ready = false

    @Volatile var problem: String? = null
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val language = tts.setLanguage(Locale("cs", "CZ"))
            ready = language >= 0
            if (!ready) problem = "Telefon neumí mluvit česky."
            // Media, like Claude's voice: the volume tool and the side buttons then set both.
            tts.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) { speaking.remove(utteranceId)?.countDown() }
                @Deprecated("TTS API")
                override fun onError(utteranceId: String) { speaking.remove(utteranceId)?.countDown() }
            })
            Log.i(Config.TAG, "Porada: hlas ${tts.defaultEngine}, čeština $language")
        } else {
            problem = "Hlas telefonu se nespustil."
        }
        initialized.countDown()
    }

    /** Returns once the sentence has been spoken; call off the main thread. */
    fun say(text: String) {
        Log.i(Config.TAG, "Porada: říkám „$text“")
        if (!initialized.await(INIT_WAIT_S, TimeUnit.SECONDS) || !ready) {
            Log.w(Config.TAG, "Porada: nemluvím, ${problem ?: "hlas se nenačetl"}")
            return
        }
        val id = "porada-${ids.incrementAndGet()}"
        val done = CountDownLatch(1)
        speaking[id] = done
        val queued = if (quietDir != null) {
            tts.synthesizeToFile(text, null, File(quietDir, "hlas-$id.wav"), id)
        } else {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }
        if (queued != TextToSpeech.SUCCESS) {
            speaking.remove(id)
            Log.w(Config.TAG, "Porada: hlas odmítl větu")
            return
        }
        done.await(SPEAK_WAIT_S + text.length / 5, TimeUnit.SECONDS)
    }

    fun announce(text: String) {
        thread(name = "porada-hlas") { say(text) }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        const val INIT_WAIT_S = 10L
        const val SPEAK_WAIT_S = 20L
    }
}
