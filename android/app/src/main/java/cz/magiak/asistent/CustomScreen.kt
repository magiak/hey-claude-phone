package cz.magiak.asistent

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale

/** The home page Claude wrote for the user. It replaces the clock until it is changed or reset. */
object CustomScreen {

    private const val FILE = "screen.html"
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

    /** The native dashboard as a page in the current look, so asking for one more thing keeps the clock. */
    fun original(context: Context): String =
        context.resources.openRawResource(R.raw.clock).bufferedReader().use { it.readText() }
            .replace("/*colors*/", palette(context))
            .replace("{lang}", Locale.getDefault().toLanguageTag())
            .replace("{ask_title}", js(context.getString(R.string.ask_title)))
            .replace("{ask_hint}", js(context.getString(R.string.ask_hint, Config.WAKE_LABEL)))
            .replace("{last_title}", js(context.getString(R.string.last_title, "{ago}")))
            .replace("{ago_now}", js(context.getString(R.string.ago_now)))
            .replace("{ago_min}", js(context.getString(R.string.ago_min, "{n}")))
            .replace("{ago_hours}", js(context.getString(R.string.ago_hours, "{n}")))

    // Body of a single-quoted JS string literal.
    private fun js(text: String) = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    fun palette(context: Context): String {
        val dark = UserSettings.dark(context)
        fun c(light: Int, darkColor: Int) = "#%06X".format(context.getColor(if (dark) darkColor else light) and 0xFFFFFF)
        return ":root { --surface: ${c(R.color.surface, R.color.surface_dark)}; --card: ${c(R.color.card, R.color.card_dark)}; " +
            "--clock: ${c(R.color.clock, R.color.clock_dark)}; --text: ${c(R.color.text, R.color.text_dark)}; " +
            "--muted: ${c(R.color.muted, R.color.muted_dark)}; --title: ${c(R.color.title, R.color.title_dark)}; }"
    }

    private fun changed() {
        revision++
        main.post { onChange?.invoke() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
