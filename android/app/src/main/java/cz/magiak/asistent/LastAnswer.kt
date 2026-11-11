package cz.magiak.asistent

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The last question and Claude's answer, read from the chat after a call ends, so a recipe
 * or a number stays readable on the display after the voice is gone.
 */
data class LastAnswer(val question: String, val answer: String, val at: Long) {

    companion object {
        private const val PREFS = "last_answer"

        fun load(context: Context): LastAnswer? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val answer = p.getString("answer", null) ?: return null
            val at = p.getLong("at", 0L)
            if (System.currentTimeMillis() - at > Config.LAST_ANSWER_HOURS * 3_600_000L) return null
            return LastAnswer(p.getString("question", "").orEmpty(), answer, at)
        }

        fun save(context: Context, value: LastAnswer) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("question", value.question)
                .putString("answer", value.answer)
                .putLong("at", value.at)
                .apply()
        }

        /**
         * Layout read from the Claude app on 2026-11-08: his messages are right-aligned, Claude's
         * start at the left edge and may be split into several text nodes (paragraphs, lists).
         * A call usually ends with "goodbye", so the last answer long enough to matter wins.
         */
        fun fromChat(root: AccessibilityNodeInfo, nodes: Sequence<AccessibilityNodeInfo>): LastAnswer? {
            val screen = Rect().also(root::getBoundsInScreen)
            val w = screen.width().toFloat()
            val texts = nodes.mapNotNull { node ->
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isEmpty() || node.isEditable || isAppText(text)) return@mapNotNull null
                val b = Rect().also(node::getBoundsInScreen)
                if (b.top < screen.top + screen.height() * 0.08) return@mapNotNull null
                when {
                    b.left < screen.left + w * 0.15 -> Triple(b.top, false, text)
                    b.left > screen.left + w * 0.3 && b.right > screen.left + w * 0.85 -> Triple(b.top, true, text)
                    else -> null
                }
            }.sortedBy { it.first }.toList()

            val exchanges = mutableListOf<Pair<String, StringBuilder>>()
            for ((_, mine, text) in texts) {
                if (mine) exchanges += text to StringBuilder()
                else if (exchanges.isEmpty()) exchanges += "" to StringBuilder(text)
                else exchanges.last().second.apply { if (isNotEmpty()) append('\n') }.append(text)
            }
            val best = exchanges.lastOrNull { it.second.length >= Config.MIN_ANSWER_CHARS } ?: return null
            return LastAnswer(best.first, best.second.toString(), System.currentTimeMillis())
        }

        // Claude app chrome that sits at the left edge like an answer; it filled the card after a
        // volume call on 2026-11-09: "Voice chat ended", "46s", tool "Volume", connector
        // initial "D", the "Claude is AI…" footer.
        private val DURATION = Regex("""^\d+\s?(s|m|min|h)(\s?\d+\s?s)?$""")
        // Titles of his connector's tools as the Claude app shows them on a tool call; Tools fills this
        // from the string resources so no Context is needed here.
        @Volatile var toolTitles: Set<String> = emptySet()

        private fun isAppText(text: String) =
            text.length <= 2 ||
                text.startsWith("Voice chat", ignoreCase = true) ||
                text.startsWith("Claude is AI", ignoreCase = true) ||
                text.contains("can make mistakes", ignoreCase = true) ||
                DURATION.matches(text) ||
                text in toolTitles
    }
}
