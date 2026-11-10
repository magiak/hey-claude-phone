package cz.magiak.asistent.meeting

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** What the dashboard shows about the meeting; the service writes, the activity reads. */
object Meeting {
    enum class State { IDLE, RECORDING, FINISHING }

    @Volatile var state = State.IDLE
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var question: String? = null
        private set
    @Volatile var answer: String? = null
        private set
    @Volatile var note: String? = null
        private set

    /** Main thread. */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    val recording get() = state == State.RECORDING

    fun started(at: Long) {
        state = State.RECORDING
        startedAt = at
        question = null
        answer = null
        note = null
        changed()
    }

    fun finishing() {
        state = State.FINISHING
        changed()
    }

    fun ended() {
        state = State.IDLE
        note = null
        changed()
    }

    fun answered(question: String, answer: String) {
        this.question = question
        this.answer = answer
        changed()
    }

    fun note(text: String?) {
        if (note == text) return
        note = text
        changed()
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }
}

/** Past meetings on the phone, one folder each: zaznam.wav, prepis.md, shrnuti.md. */
object MeetingArchive {
    fun root(context: Context) = File(context.getExternalFilesDir(null), "porady")

    private fun all(context: Context) =
        root(context).listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }

    /** Summaries of the latest meetings, or the meetings where [query] was said, for Claude to answer from. */
    fun find(context: Context, query: String): String {
        val q = query.trim()
        val hits = all(context).asSequence().mapNotNull { dir ->
            val summary = File(dir, "shrnuti.md").takeIf(File::exists)?.readText()?.trim() ?: "Shrnutí ještě není."
            if (q.isEmpty()) return@mapNotNull dir to summary
            val lines = File(dir, "prepis.md").takeIf(File::exists)?.readLines().orEmpty().filter { it.contains(q, ignoreCase = true) }
            if (lines.isEmpty() && !summary.contains(q, ignoreCase = true)) null
            else dir to (summary + if (lines.isEmpty()) "" else "\n\nZ přepisu:\n" + lines.take(MAX_LINES).joinToString("\n"))
        }.take(if (q.isEmpty()) LATEST else MAX_HITS).toList()
        if (hits.isEmpty()) return if (q.isEmpty()) "Žádná porada zatím nahraná není." else "„$q“ v žádné poradě nezaznělo."
        return hits.joinToString("\n\n---\n\n") { (dir, text) -> "Porada ${dir.name} (id ${dir.name}):\n$text" }
    }

    fun transcript(context: Context, id: String): String? =
        all(context).firstOrNull { it.name == id }?.let { File(it, "prepis.md") }?.takeIf(File::exists)?.readText()

    private const val LATEST = 3
    private const val MAX_HITS = 5
    private const val MAX_LINES = 8
}
