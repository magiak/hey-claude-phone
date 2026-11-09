package cz.magiak.asistent

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** The home page Claude wrote for the user. It replaces the clock until it is changed or reset. */
object CustomScreen {

    private const val FILE = "obrazovka.html"
    private val main = Handler(Looper.getMainLooper())

    // Tools run on the relay thread, the dashboard on the main one; it reloads when this moves.
    @Volatile var revision = 0
        private set

    @Volatile var onChange: (() -> Unit)? = null

    fun load(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun save(context: Context, html: String) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(html)
        tmp.renameTo(file(context))
        changed()
    }

    fun reset(context: Context) {
        file(context).delete()
        changed()
    }

    private fun changed() {
        revision++
        main.post { onChange?.invoke() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
