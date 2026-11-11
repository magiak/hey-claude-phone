package cz.magiak.asistent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import cz.magiak.asistent.meeting.Meeting
import cz.magiak.asistent.meeting.MeetingService

class SettingsActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))


    // Only the dashboard listens, so a settings screen left open would leave the room deaf.
    private val backToDashboard = Runnable { finish() }
    private lateinit var page: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.BLACK
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(40)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(getString(R.string.settings), 28f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = getString(R.string.done)
            setOnClickListener { finish() }
        })
        page.addView(header)

        page.addView(Switch(this).apply {
            text = getString(R.string.new_chat)
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = UserSettings.newChat(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> UserSettings.setNewChat(this@SettingsActivity, on) }
        }, below(16))
        page.addView(label(getString(R.string.new_chat_hint), 14f, GRAY))

        val silence = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        silence.addView(label(getString(R.string.end_call_after_silence), 20f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val choices = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = UserSettings.callIdleSeconds(this)
        UserSettings.CALL_IDLE_CHOICES.forEach { seconds ->
            choices.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = if (seconds < 60) "$seconds s" else "${seconds / 60} min"
                textSize = 18f
                setTextColor(Color.WHITE)
                isChecked = seconds == current
                setOnClickListener { UserSettings.setCallIdleSeconds(this@SettingsActivity, seconds) }
            })
        }
        silence.addView(choices)
        page.addView(silence, below(24))

        page.addView(Switch(this).apply {
            text = getString(R.string.dark_theme)
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = UserSettings.dark(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> UserSettings.setDark(this@SettingsActivity, on) }
        }, below(24))
        page.addView(label(getString(R.string.dark_theme_hint), 14f, GRAY))

        val language = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        language.addView(label(getString(R.string.language), 20f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val languages = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val chosen = UserSettings.language(this)
        Language.CODES.forEach { code ->
            languages.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = LANGUAGE_NAMES.getValue(code)
                textSize = 18f
                setTextColor(Color.WHITE)
                isChecked = code == chosen
                setOnClickListener {
                    if (code == UserSettings.language(this@SettingsActivity)) return@setOnClickListener
                    UserSettings.setLanguage(this@SettingsActivity, code)
                    Language.applyToApp(this@SettingsActivity)
                    recreate()
                }
            })
        }
        language.addView(languages)
        page.addView(language, below(24))

        // Every dashboard route into Claude starts a call, and ending it jumps back to the
        // clock, so Claude's own settings were out of reach.
        page.addView(Button(this).apply {
            text = getString(R.string.open_claude_plain)
            setOnClickListener { openClaudePlain() }
        }, below(32))
        page.addView(label(getString(R.string.open_claude_plain_hint), 14f, GRAY))

        // The spoken start and end are English phrases nobody has tried with his voice yet.
        page.addView(Button(this).apply {
            text = getString(if (Meeting.state == Meeting.State.IDLE) R.string.meeting_record else R.string.meeting_end)
            setOnClickListener {
                if (Meeting.state == Meeting.State.IDLE) MeetingService.start(this@SettingsActivity) else MeetingService.stop(this@SettingsActivity)
                finish()
            }
        }, below(32))
        page.addView(label(getString(R.string.meeting_voice_hint, Config.WAKE_LABEL), 14f, GRAY))

        page.addView(Button(this).apply {
            text = getString(R.string.reset_defaults)
            setOnClickListener { confirmReset() }
        }, below(32))
        page.addView(label(getString(R.string.reset_defaults_hint), 14f, GRAY))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(page, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        onUserInteraction()
    }

    override fun onPause() {
        page.removeCallbacks(backToDashboard)
        super.onPause()
    }

    override fun onUserInteraction() {
        page.removeCallbacks(backToDashboard)
        page.postDelayed(backToDashboard, IDLE_MS)
    }

    private fun openClaudePlain() {
        val launch = packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE) ?: return
        // Without a pending launch the voice service leaves Claude alone.
        Session.launchRequestedAt = 0L
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset_confirm_ok) { _, _ ->
                CustomScreen.reset(this)
                UserSettings.reset(this)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun below(marginDp: Int) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(marginDp) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val IDLE_MS = 60_000L
        val GRAY = Color.rgb(0x9E, 0x9E, 0x9E)
        // Each in its own language, so the right one is findable whatever the current one is.
        val LANGUAGE_NAMES = mapOf("en" to "English", "cs" to "Čeština")
    }
}
