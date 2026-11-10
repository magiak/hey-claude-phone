package cz.magiak.asistent.meeting

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import cz.magiak.asistent.BuildConfig
import cz.magiak.asistent.Config
import java.time.Duration

/** Answers questions about the meeting and writes its summary. */
interface Answerer {
    /** Null means stay silent: the wake word was not a question for Claude. */
    fun answer(transcript: String, question: String): String?
    fun summarize(transcript: String): String
}

class AnswerFailed(message: String) : Exception(message)

class ClaudeAnswerer(private val key: String = BuildConfig.ANTHROPIC_KEY) : Answerer {

    private val client: AnthropicClient by lazy {
        if (key.isBlank()) throw AnswerFailed("Chybí klíč pro Clauda.")
        AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(TIMEOUT_S)).maxRetries(1).build()
    }

    override fun answer(transcript: String, question: String): String? {
        val text = ask(
            ANSWER_PROMPT,
            "<prepis>\n$transcript\n</prepis>\n\nOtázka: $question",
            maxTokens = 4_000,
            effort = BetaOutputConfig.Effort.LOW,
        )
        return text.takeUnless { it.trim().trimEnd('.').equals(SILENT, ignoreCase = true) }
    }

    override fun summarize(transcript: String): String =
        ask(SUMMARY_PROMPT, "<prepis>\n$transcript\n</prepis>", maxTokens = 16_000, effort = null)

    private fun ask(system: String, user: String, maxTokens: Long, effort: BetaOutputConfig.Effort?): String {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessage(user)
            .apply { if (effort != null) outputConfig(BetaOutputConfig.builder().effort(effort).build()) }
            // A safety classifier can decline a harmless meeting; the server then retries on another model.
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .build()
        val message = client.beta().messages().create(params)
        val stop = message.stopReason().orElse(null)
        Log.i(Config.TAG, "Porada: Claude skončil $stop, ${message.usage().inputTokens()} + ${message.usage().outputTokens()} tokenů")
        if (stop == BetaStopReason.REFUSAL) throw AnswerFailed("Claude odmítl odpovědět.")
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isEmpty()) throw AnswerFailed(if (stop == BetaStopReason.MAX_TOKENS) "Claude to nestihl dopsat." else "Claude neodpověděl.")
        return text
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val TIMEOUT_S = 120L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val SILENT = "TICHO"

        val ANSWER_PROMPT = """
            Jsi hlasový asistent, který na poradě tiše poslouchá a odpovídá jen na otázky položené přímo tobě.
            Dostaneš dosavadní automatický přepis porady a otázku. Přepis může mít chyby a označení mluvčích
            se mezi úseky nemusí shodovat. Řádky „Otázka pro Clauda“ a „Claude:“ jsou tvoje dřívější odpovědi.
            Odpověz jazykem otázky, jednou až třemi krátkými větami, k věci. Odpověď se čte nahlas:
            žádný markdown, odrážky ani emoji. Když odpověď v přepisu není, řekni to jednou větou.
            Když text ve skutečnosti není otázka ani pokyn pro tebe, třeba se o tobě jen mluvilo,
            odpověz jen slovem $SILENT.
        """.trimIndent()

        val SUMMARY_PROMPT = """
            Z automatického přepisu porady napiš krátké shrnutí česky v markdownu.
            Nejdřív dvě až čtyři věty, o čem porada byla. Pak nadpis „## Rozhodnutí“ a odrážky,
            pak „## Úkoly“ a odrážky: co, kdo a do kdy, jen pokud to zaznělo. Když rozhodnutí nebo úkoly
            nezazněly, napiš pod nadpis „Nezaznělo.“ Nic nepřidávej, co v přepisu není.
            Přepis může mít chyby a označení mluvčích se mezi úseky nemusí shodovat.
            Řádky „Otázka pro Clauda“ a „Claude:“ a hlas asistenta v přepisu nejsou účastník porady.
        """.trimIndent()
    }
}

/** Debug stand-in: lets the whole meeting flow run on the phone without an API key. */
class FakeAnswerer : Answerer {
    override fun answer(transcript: String, question: String) = "Zkušební odpověď. Slyšel jsem otázku: $question"

    override fun summarize(transcript: String) =
        "Zkušební shrnutí bez Clauda. Přepis má ${transcript.lines().count { it.startsWith("[") }} řádků.\n\n" +
            "## Rozhodnutí\n\nNezaznělo.\n\n## Úkoly\n\nNezaznělo."
}
