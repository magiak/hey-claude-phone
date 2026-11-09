package cz.magiak.asistent

import android.app.Application
import cz.magiak.asistent.mcp.Konektor

/** The relay link lives with the process, not with the dashboard or the accessibility service. */
class AsistentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Konektor.start(this)
    }
}
