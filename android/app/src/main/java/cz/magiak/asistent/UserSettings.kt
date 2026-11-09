package cz.magiak.asistent

import android.content.Context

/** What can be changed on the phone itself; Config holds what only a rebuild changes. */
object UserSettings {
    private const val PREFS = "settings"
    private const val NEW_CHAT = "new_chat"
    private const val CALL_IDLE_S = "call_idle_s"
    private const val DARK = "dark"

    // 15 s of quiet is plenty, a minute felt long (his call, 2026-11-09).
    val CALL_IDLE_CHOICES = listOf(15, 30, 60)
    private const val DEFAULT_CALL_IDLE_S = 15

    fun newChat(c: Context) = prefs(c).getBoolean(NEW_CHAT, true)

    fun setNewChat(c: Context, value: Boolean) = prefs(c).edit().putBoolean(NEW_CHAT, value).apply()

    fun callIdleSeconds(c: Context) = prefs(c).getInt(CALL_IDLE_S, DEFAULT_CALL_IDLE_S)

    fun setCallIdleSeconds(c: Context, value: Int) = prefs(c).edit().putInt(CALL_IDLE_S, value).apply()

    // Light matches his white holder; dark is for a black one (2026-11-09).
    fun dark(c: Context) = prefs(c).getBoolean(DARK, false)

    fun setDark(c: Context, value: Boolean) = prefs(c).edit().putBoolean(DARK, value).apply()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
