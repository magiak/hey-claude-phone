package cz.magiak.asistent

import android.graphics.Color

object Config {
    const val TAG = "Asistent"

    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    // An app link the Claude app handles itself (its manifest, app 1.261104.19): each call starts
    // in an empty chat instead of continuing the last one (his wish, 2026-11-09).
    const val NEW_CHAT_URL = "https://claude.ai/new"

    // Tuning the wake word: the dashboard in trial mode and `adb logcat -s Asistent` show every heard word
    // with its confidence. The recognizer may only output these words; decoys give similar
    // sounds somewhere else to land, so not every "klaud"-like noise becomes "claude".
    // Two words in a row, each above the bar: one syllable mixed too easily with "cloud" and the
    // like (his call, 2026-11-08). Every word must be in the English model's vocabulary
    // ("jarvis" is not), and each phrase word also stands alone in the grammar, so a plain
    // "Claude" or a Czech "hej" lands there instead of passing; only "claude" flashes red.
    // His recording 2026-11-08 19:44: every "hey claude" that passed had both words at 0.77 and up;
    // in his Czech talk "hey" came at 0.51-0.56 and would have opened Claude, so the bar sits at 0.6.
    // Decoys only added red flashes during talk (3 against 1 in 30 s) and caught nothing extra.
    val WAKE_PHRASE = listOf("hey", "claude")
    const val WAKE_LABEL = "Hey Claude"
    val DECOY_WORDS = listOf<String>()
    const val MIN_CONFIDENCE = 0.6

    // Exact labels read from the Claude app on 2026-11-08 (English UI). Must stay exact:
    // the call screen also has "Mute Claude's voice", which a loose match would take for
    // the voice-mode button and end the call at once. Czech variants are guesses.
    val VOICE_BUTTON = Regex("^(voice mode|hlasový režim)$", RegexOption.IGNORE_CASE)
    val STOP_BUTTON = Regex("^(stop|zastavit|ukončit)$", RegexOption.IGNORE_CASE)

    const val CLICK_TIMEOUT_MS = 15_000L
    const val NEW_CHAT_SETTLE_MS = 1_000L

    // Voice mode never ends by itself and keeps the mic, so the service stops it after the quiet
    // chosen in settings; this caps a call whose screen never stops changing.
    const val MAX_CALL_MS = 15 * 60_000L

    // The last answer stays on the display. It is his private chat on a shared screen:
    // false hides it. Short answers ("goodbye") are skipped in favour of the one before.
    const val SHOW_LAST_ANSWER = true
    const val LAST_ANSWER_HOURS = 12
    const val MIN_ANSWER_CHARS = 40

    // Edge glow: 0..1 strength while someone speaks. The wake word flashes green; a near miss
    // ("claude" under the bar, or a decoy) flashes red so he knows to say it again.
    // If conversation in the room makes it flash red too often, SHOW_NEAR_MISS = false.
    val GLOW_COLOR = Color.rgb(0xD9, 0x77, 0x57)
    const val GLOW_VOICE = 0.45f
    val GLOW_HIT = Color.rgb(0x43, 0xA0, 0x47)
    val GLOW_MISS = Color.rgb(0xE5, 0x39, 0x35)
    const val GLOW_MISS_LEVEL = 0.7f
    const val SHOW_NEAR_MISS = true

    // Beep on the wake word, on the media volume like Claude's voice; 0..100 of that volume.
    const val BEEP_ON_WAKE = true
    const val BEEP_VOLUME = 80

    // At night the screen would light up the room. 0 can switch the backlight off on some phones.
    const val NIGHT_FROM_HOUR = 22
    const val NIGHT_TO_HOUR = 7
    const val NIGHT_BRIGHTNESS = 0.03f
}

/** Shared between the activity and the accessibility service; both run in one process. */
object Session {
    @Volatile var launchRequestedAt: Long = 0L

    @Volatile var serviceConnected = false

    // Shown on the dashboard until voice mode starts again (login screen, update dialog...).
    @Volatile var problem: String? = null
}
