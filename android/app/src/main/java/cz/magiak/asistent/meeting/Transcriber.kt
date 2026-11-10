package cz.magiak.asistent.meeting

import cz.magiak.asistent.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.TimeUnit

/** Times are seconds from the start of the audio handed in, or of the meeting once shifted. */
data class Word(val text: String, val start: Double, val end: Double, val speaker: String?) {
    fun shifted(by: Double) = copy(start = start + by, end = end + by)
}

interface Transcriber {
    /** [wav] is a whole WAV file, 16 kHz mono. */
    fun transcribe(wav: ByteArray): List<Word>
}

class TranscribeFailed(message: String) : Exception(message)

class ScribeTranscriber(private val key: String = BuildConfig.ELEVENLABS_KEY) : Transcriber {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // No language_code: Scribe detects it, and his meetings mix Czech and English.
    override fun transcribe(wav: ByteArray): List<Word> {
        if (key.isBlank()) throw TranscribeFailed("Chybí klíč pro přepis.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", "scribe_v2")
            .addFormDataPart("diarize", "true")
            .addFormDataPart("timestamps_granularity", "word")
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("file", "porada.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(URL).header("xi-api-key", key).post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw TranscribeFailed("Přepis selhal (${response.code}).")
                val words = JSONObject(text).optJSONArray("words") ?: return emptyList()
                return (0 until words.length()).map(words::getJSONObject)
                    .filter { it.optString("type") == "word" }
                    .map { Word(it.getString("text"), it.optDouble("start"), it.optDouble("end"), it.optString("speaker_id").ifBlank { null }) }
            }
        } catch (e: java.io.IOException) {
            throw TranscribeFailed("Přepis nejde, telefon je asi bez internetu.")
        }
    }

    private companion object {
        const val URL = "https://api.elevenlabs.io/v1/speech-to-text"
    }
}

/** Debug stand-in for Scribe: the offline English model, good enough for synthetic English test audio. */
class VoskTranscriber(private val model: Model) : Transcriber {
    override fun transcribe(wav: ByteArray): List<Word> {
        val words = mutableListOf<Word>()
        fun take(json: String) {
            val result = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val w = result.getJSONObject(i)
                words += Word(w.getString("word"), w.getDouble("start"), w.getDouble("end"), null)
            }
        }
        Recognizer(model, MeetingService.SAMPLE_RATE.toFloat()).use { recognizer ->
            recognizer.setWords(true)
            var pos = Wav.HEADER
            while (pos < wav.size) {
                val n = minOf(STEP_BYTES, wav.size - pos)
                if (recognizer.acceptWaveForm(wav.copyOfRange(pos, pos + n), n)) take(recognizer.result)
                pos += n
            }
            take(recognizer.finalResult)
        }
        return words
    }

    private companion object {
        const val STEP_BYTES = 6_400
    }
}
