package cz.magiak.asistent

import android.app.Application
import android.content.Context
import cz.magiak.asistent.mcp.Connector
import java.io.File

/** The relay link lives with the process, not with the dashboard or the accessibility service. */
class AssistantApp : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate() {
        super.onCreate()
        moveCzechNamedFiles()
        Connector.start(this)
    }

    // Builds before 2026-11-11 named their files in Czech; this keeps meetings, the custom screen and
    // the last answer of a phone that ran one. Safe to drop once no such phone is left.
    private fun moveCzechNamedFiles() {
        File(dataDir, "shared_prefs/posledni.xml").moveTo(File(dataDir, "shared_prefs/last_answer.xml"))
        File(filesDir, "obrazovka.html").moveTo(File(filesDir, "screen.html"))
        val external = getExternalFilesDir(null) ?: return
        File(external, "obrazovka.html").moveTo(File(external, "screen.html"))
        val meetings = File(external, "meetings")
        File(external, "porady").listFiles()?.forEach { old ->
            val dir = File(meetings, old.name).apply { mkdirs() }
            mapOf("zaznam.wav" to "audio.wav", "prepis.md" to "transcript.md", "shrnuti.md" to "summary.md")
                .forEach { (from, to) -> File(old, from).moveTo(File(dir, to)) }
            old.listFiles()?.forEach { it.moveTo(File(dir, it.name)) }
            old.delete()
        }
        File(external, "porady").delete()
        external.listFiles { f -> f.name.startsWith("nahravka-") }?.forEach {
            it.moveTo(File(external, it.name.replace("nahravka-", "recording-").replace("-telefon.txt", "-phone.txt")))
        }
    }

    private fun File.moveTo(target: File) {
        if (exists() && !target.exists()) renameTo(target)
    }
}
