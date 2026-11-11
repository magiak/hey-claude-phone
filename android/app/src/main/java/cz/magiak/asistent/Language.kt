package cz.magiak.asistent

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language, chosen in settings: wall phones often run an English system for the
 * Claude app's sake while the household wants the dashboard in its own language.
 */
object Language {
    val CODES = listOf("en", "cs")

    fun wrap(base: Context): Context = forCode(base, UserSettings.language(base)).also { Locale.setDefault(Locale(UserSettings.language(base))) }

    fun forCode(base: Context, code: String): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale(code)) })

    /** After a change in settings: services and the connector use the application's resources, not an activity's. */
    @Suppress("DEPRECATION")
    fun applyToApp(context: Context) {
        val locale = Locale(UserSettings.language(context))
        Locale.setDefault(locale)
        val res = context.applicationContext.resources
        res.updateConfiguration(Configuration(res.configuration).apply { setLocale(locale) }, res.displayMetrics)
    }

    fun current(context: Context): String = context.resources.configuration.locales[0].language
}
