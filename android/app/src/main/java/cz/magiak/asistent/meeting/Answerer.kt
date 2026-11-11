package cz.magiak.asistent.meeting

import android.content.Context
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import cz.magiak.asistent.BuildConfig
import cz.magiak.asistent.Config
import cz.magiak.asistent.R
import java.time.Duration
import java.util.Locale

/** Answers questions about the meeting and writes its summary. */
interface Answerer {
    /** Null means stay silent: the wake word was not a question for Claude. */
    fun answer(transcript: String, question: String): String?
    fun summarize(transcript: String): String
}

/**
 * A failure that is spoken to the user, so it carries a string resource and the text follows the
 * phone's language. The debug key check has no Context and just logs the resource id.
 */
open class MeetingFailure(val res: Int, vararg val args: Any) : Exception("string resource $res")

class AnswerFailed(res: Int, vararg args: Any) : MeetingFailure(res, *args)

/**
 * The words the transcript and the summary use for their own structure. The prompts quote the same
 * words, so what Claude is told to look for is what the code wrote.
 */
class MeetingLabels(val question: String, val decisions: String, val tasks: String, val none: String) {
    companion object {
        fun of(context: Context) = MeetingLabels(
            context.getString(R.string.meeting_question_prefix),
            context.getString(R.string.meeting_decisions),
            context.getString(R.string.meeting_tasks),
            context.getString(R.string.meeting_none_said),
        )

        // The debug key check has no Context; it only needs transcript and prompt to agree.
        val ENGLISH = MeetingLabels("Question for Claude:", "Decisions", "Tasks", "Nothing was said.")
    }
}

class ClaudeAnswerer(
    private val key: String = BuildConfig.ANTHROPIC_KEY,
    private val labels: MeetingLabels = MeetingLabels.ENGLISH,
) : Answerer {

    private val client: AnthropicClient by lazy {
        if (key.isBlank()) throw AnswerFailed(R.string.meeting_no_key_claude)
        AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(TIMEOUT_S)).maxRetries(1).build()
    }

    override fun answer(transcript: String, question: String): String? {
        val text = ask(
            answerPrompt(),
            "<transcript>\n$transcript\n</transcript>\n\nQuestion: $question",
            maxTokens = 4_000,
            effort = BetaOutputConfig.Effort.LOW,
        )
        return text.takeUnless { it.trim().trimEnd('.').equals(SILENT, ignoreCase = true) }
    }

    override fun summarize(transcript: String): String =
        ask(summaryPrompt(), "<transcript>\n$transcript\n</transcript>", maxTokens = 16_000, effort = null)

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
        Log.i(Config.TAG, "Meeting: Claude finished with $stop, ${message.usage().inputTokens()} + ${message.usage().outputTokens()} tokens")
        if (stop == BetaStopReason.REFUSAL) throw AnswerFailed(R.string.meeting_refused)
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isEmpty()) throw AnswerFailed(if (stop == BetaStopReason.MAX_TOKENS) R.string.meeting_cut_off else R.string.meeting_no_answer)
        return text
    }

    private fun answerPrompt() = """
        You are a voice assistant that quietly listens in a meeting and answers only questions asked directly to you.
        You get the automatic transcript so far and a question. The transcript may contain errors and the
        speaker labels need not match between sections. Lines starting with "${labels.question}" and "Claude:" are your earlier answers.
        Answer in the language of the question, in one to three short sentences, to the point. The answer is read aloud:
        no markdown, bullets or emoji. If the answer is not in the transcript, say so in one sentence.
        If the text is not actually a question or request to you, for example you were only talked about,
        reply with just the word $SILENT.
    """.trimIndent()

    // The phone's language, so the summary reads like the headings quoted below.
    private fun summaryPrompt() = """
        From the automatic meeting transcript write a short summary in markdown, in ${Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)}.
        First two to four sentences on what the meeting was about. Then the heading "## ${labels.decisions}" and bullets,
        then "## ${labels.tasks}" and bullets: what, who and by when, only if it was said. If no decisions or tasks
        were said, write "${labels.none}" under the heading. Use these headings exactly as quoted. Add nothing that is not in the transcript.
        The transcript may contain errors and the speaker labels need not match between sections.
        Lines starting with "${labels.question}" and "Claude:" and the assistant's voice in the transcript are not meeting participants.
    """.trimIndent()

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val TIMEOUT_S = 120L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val SILENT = "SILENT"
    }
}

/** Debug stand-in: lets the whole meeting flow run on the phone without an API key. */
class FakeAnswerer(private val context: Context) : Answerer {
    private val labels = MeetingLabels.of(context)

    override fun answer(transcript: String, question: String) = context.getString(R.string.meeting_fake_answer, question)

    override fun summarize(transcript: String) =
        context.getString(R.string.meeting_fake_summary, transcript.lines().count { it.startsWith("[") }) +
            "\n\n## ${labels.decisions}\n\n${labels.none}\n\n## ${labels.tasks}\n\n${labels.none}"
}
