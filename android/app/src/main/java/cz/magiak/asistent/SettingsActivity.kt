package cz.magiak.asistent

import android.app.Activity
import android.app.AlertDialog
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

class SettingsActivity : Activity() {

    // Only the dashboard listens, so a settings screen left open would leave the room deaf.
    private val backToDashboard = Runnable { finish() }
    private lateinit var page: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.BLACK
        page =LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(40)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Nastavení", 28f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Hotovo"
            setOnClickListener { finish() }
        })
        page.addView(header)

        page.addView(Switch(this).apply {
            text = "Každý hovor v nové konverzaci"
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = UserSettings.newChat(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> UserSettings.setNewChat(this@SettingsActivity, on) }
        }, below(16))
        page.addView(label("Vypnuté: pokračuje v poslední konverzaci a otevře se o chvilku rychleji.", 14f, GRAY))

        val silence = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        silence.addView(label("Ukončit hovor po tichu", 20f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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
            text = "Tmavý vzhled"
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = UserSettings.dark(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> UserSettings.setDark(this@SettingsActivity, on) }
        }, below(24))
        page.addView(label("Pro černý rámeček. Světlý sedí k bílému.", 14f, GRAY))

        // Every dashboard route into Claude starts a call, and ending it jumps back to the
        // clock, so Claude's own settings were out of reach.
        page.addView(Button(this).apply {
            text = "Otevřít Clauda bez hovoru"
            setOnClickListener { openClaudePlain() }
        }, below(32))
        page.addView(label("Na nastavení Clauda nebo přihlášení. Zpátky na hodiny tlačítkem Domů.", 14f, GRAY))

        page.addView(Button(this).apply {
            text = "Obnovit výchozí"
            setOnClickListener { confirmReset() }
        }, below(32))
        page.addView(label("Hodiny místo vlastní obrazovky a nastavení jako na začátku.", 14f, GRAY))

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
            .setMessage("Vrátit hodiny a výchozí nastavení? Vlastní obrazovka se smaže.")
            .setPositiveButton("Obnovit") { _, _ ->
                CustomScreen.reset(this)
                UserSettings.reset(this)
                finish()
            }
            .setNegativeButton("Zrušit", null)
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
    }
}
