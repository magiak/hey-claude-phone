package cz.magiak.asistent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import cz.magiak.asistent.mcp.Konektor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Always-on dashboard. Listening lives here, not in a service: while this activity is in
 * front it may use the mic and start Claude, and leaving it (onPause) frees the mic.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var glow: GlowView
    private lateinit var cardTitle: TextView
    private lateinit var question: TextView
    private lateinit var answer: TextView
    private lateinit var date: TextView
    private var demoAnswer: LastAnswer? = null
    private var trial = false
    private var armed = false
    private var recording: VoiceRecording? = null
    private var recordNote: String? = null
    private var listenStatus = ""
    private var answerSize = 0f
    private var beeper: ToneGenerator? = null
    private lateinit var listener: WakeWordListener
    private var askedForMic = false
    private var screenLock: PowerManager.WakeLock? = null

    private val launchAfterFlash = Runnable { openClaude() }
    private val listenAgain = Runnable { listener.start() }
    private val hideBarsAgain = Runnable { hideSystemBars() }
    private val everyMinute = object : Runnable {
        override fun run() {
            applyNight()
            date.text = DATE.format(Date()).replaceFirstChar { it.uppercase() }
            if (!armed && recording == null) showLastAnswer(demoAnswer ?: LastAnswer.load(this@MainActivity))
            updateStatus()
            glow.postDelayed(this, 60_000)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window flag only covers the dashboard. With Claude in front and the cable dropping
        // out, the 60 s timeout ran and the phone woke to the PIN screen (2026-11-08).
        @Suppress("DEPRECATION")
        screenLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "asistent:obrazovka")
            .apply { setReferenceCounted(false); acquire() }
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        glow = findViewById(R.id.glow)
        cardTitle = findViewById(R.id.card_title)
        question = findViewById(R.id.question)
        answer = findViewById(R.id.answer)
        date = findViewById(R.id.date)
        answerSize = answer.textSize
        window.decorView.setBackgroundColor(getColor(R.color.background))
        // Otherwise the strip beside the camera notch stays black next to the light background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // On a fresh start the system clears the hide flags again and the navigation bar stays
        // (ZenFone, 2026-11-08); a desk device has nobody to swipe it away.
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                glow.removeCallbacks(hideBarsAgain)
                glow.postDelayed(hideBarsAgain, 2_000)
            }
        }
        // The audio system may refuse another player; a missing beep must not take the dashboard down.
        if (Config.BEEP_ON_WAKE) beeper = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, Config.BEEP_VOLUME) }.getOrNull()
        val heard = findViewById<TextView>(R.id.heard)
        listener = WakeWordListener(
            this,
            onWake = ::onWake,
            onVoice = glow::voice,
            onMiss = { if (Config.SHOW_NEAR_MISS) glow.miss() },
            onStatus = { listenStatus = it; updateStatus() },
            onHeard = { heard.text = it },
        )

        val root = findViewById<View>(R.id.root)
        root.setOnClickListener {
            when {
                armed -> startRecording()
                recording == null -> openClaude()
            }
        }
        root.setOnLongClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        showDemo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDemo(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Session.launchRequestedAt = 0L
        glow.reset()
        everyMinute.run()
        refresh()
    }

    private fun refresh() {
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                if (askedForMic) {
                    status.text = "Bez mikrofonu to nepůjde. Povol ho v nastavení aplikace."
                } else {
                    askedForMic = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
            packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE) == null ->
                status.text = "Chybí aplikace Claude."
            !armed && recording == null -> listener.start()
        }
    }

    // Without the service it still listens: Claude opens, only the voice-mode tap is
    // missing. A dashboard that goes deaf looked dead (2026-11-08).
    private fun updateStatus() {
        // Enabled in settings is not running: ASUS blocks the re-bind after a reinstall or reboot
        // unless the app may auto-start, and the setting kept saying "on" all evening (2026-11-08).
        val service = when {
            !isServiceEnabled() -> "Služba přístupnosti je vypnutá, hlasový režim v Claudovi zapni sám. Zapneš ji podržením prstu."
            !Session.serviceConnected -> "Služba přístupnosti je zapnutá, ale neběží. Podrž prst, vypni ji a znovu zapni."
            else -> null
        }
        val note = if (trial) "Zkouška: Clauda neotevírám, jen bliknu." else null
        val konektor = if (BuildConfig.KONEKTOR_URL.isNotEmpty() && !Konektor.online)
            "Konektor je odpojený: Claude teď hlasitost, tisk ani sken neovládne." else null
        status.text = listOfNotNull(Session.problem, service, konektor, note, recordNote, listenStatus).joinToString("\n")
    }

    override fun onPause() {
        listener.stop()
        recording?.cancel()
        // Armed survives a pause: the USB dialog pops up on every cable wiggle and would
        // silently turn his next tap into opening Claude.
        if (recording != null) endRecording()
        recordNote = null
        glow.removeCallbacks(launchAfterFlash)
        glow.removeCallbacks(listenAgain)
        glow.removeCallbacks(everyMinute)
        super.onPause()
    }

    override fun onDestroy() {
        screenLock?.takeIf { it.isHeld }?.release()
        beeper?.release()
        super.onDestroy()
    }

    // Launcher apps must not finish on Back, the dashboard is the home screen.
    @Deprecated("Activity API")
    override fun onBackPressed() {}

    // The flash is the only sign the word was caught, so let it show before Claude covers it.
    private fun onWake() {
        glow.wake()
        beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        if (trial) {
            glow.postDelayed(listenAgain, 1_200)
            return
        }
        status.text = "Slyším tě."
        glow.postDelayed(launchAfterFlash, 600)
    }

    private fun openClaude() {
        listener.stop()
        val launch = packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE)
        if (launch == null) {
            status.text = "Chybí aplikace Claude."
            return
        }
        status.text = "Otevírám Clauda…"
        Session.launchRequestedAt = System.currentTimeMillis()
        val target = if (UserSettings.newChat(this))
            Intent(Intent.ACTION_VIEW, Uri.parse(Config.NEW_CHAT_URL)).setPackage(Config.CLAUDE_PACKAGE)
        else launch
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showLastAnswer(last: LastAnswer?) {
        if (last == null || !Config.SHOW_LAST_ANSWER) {
            cardTitle.text = "Zeptej se"
            question.visibility = View.GONE
            answer.text = "Řekni „${Config.WAKE_LABEL}“ a ptej se nahlas. Poslední odpověď tu zůstane napsaná."
            return
        }
        cardTitle.text = "Claude naposledy · ${ago(last.at)}"
        question.visibility = if (last.question.isBlank()) View.GONE else View.VISIBLE
        question.text = last.question
        answer.text = last.answer
    }

    // The phone UI may be English; the dashboard is Czech regardless.
    private fun ago(at: Long): String {
        val min = (System.currentTimeMillis() - at) / 60_000
        return when {
            min < 1 -> "teď"
            min < 60 -> "před $min min"
            else -> "před ${min / 60} h"
        }
    }

    private fun applyNight() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = if (Config.NIGHT_FROM_HOUR > Config.NIGHT_TO_HOUR) {
            hour >= Config.NIGHT_FROM_HOUR || hour < Config.NIGHT_TO_HOUR
        } else {
            hour >= Config.NIGHT_FROM_HOUR && hour < Config.NIGHT_TO_HOUR
        }
        // The recording prompts are read from across the room.
        val brightness = if (night && !armed && recording == null) Config.NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != brightness) {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }

    // Debug builds only. hlas/slovo/vedle/odpoved: screenshots without anyone speaking.
    // zkouska: the wake word only flashes, so a round of tries for tuning does not open Claude
    // each time; the Home button ends it.
    // nahraj: a guided recording of his voice for tuning on the PC; starts on his tap.
    // prehraj: the newest recording through the current Config, no voice needed.
    // adb shell am start -n cz.magiak.asistent/.MainActivity --es ukazka zkouska
    private fun showDemo(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        trial = intent.getStringExtra("ukazka") == "zkouska"
        val show: () -> Unit = when (intent.getStringExtra("ukazka")) {
            "hlas" -> glow::voice
            "slovo" -> glow::wake
            "vedle" -> glow::miss
            "nahraj" -> {
                arm()
                return
            }
            "prehraj" -> {
                val wav = (getExternalFilesDir(null) ?: filesDir)
                    .listFiles { f -> f.name.startsWith("nahravka-") && f.name.endsWith(".wav") }
                    ?.maxByOrNull { it.name }
                recordNote = if (wav == null) "Žádná nahrávka." else "Vyhodnocuji nahrávku…"
                updateStatus()
                if (wav != null) VoiceRecording.replay(this, wav) { recordNote = it; updateStatus() }
                return
            }
            "odpoved" -> {
                demoAnswer = LastAnswer(
                    "Jak dlouho vařit vajíčko natvrdo?",
                    "Od chvíle, kdy voda začne vřít, zhruba 9 až 10 minut. Pak ho dej hned do studené vody, " +
                        "líp se loupe a žloutek nezezelená.",
                    System.currentTimeMillis() - 12 * 60_000L,
                )
                showLastAnswer(demoAnswer)
                return
            }
            else -> return
        }
        repeat(20) { glow.postDelayed(show, it * 200L) }
    }

    private fun arm() {
        listener.stop()
        armed = true
        recordNote = null
        cardTitle.text = "Nahrávání hlasu pro ladění"
        question.visibility = View.GONE
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        answer.text = "Klepni a začne nahrávání, minuta a půl. Pokaždé, když okraje bliknou zeleně, řekni jednou „${Config.WAKE_LABEL}“."
        listenStatus = ""
        updateStatus()
        applyNight()
    }

    private fun startRecording() {
        armed = false
        answer.text = ""
        recording = VoiceRecording(
            this,
            onStep = { text, prompt ->
                answer.text = text
                if (prompt) glow.wake()
            },
            onLeft = { status.text = "Nahrávám, zbývá $it s" },
            onDone = { note ->
                if (recording != null) {
                    endRecording()
                    listener.start()
                }
                recordNote = note
                updateStatus()
            },
        ).also { it.start() }
    }

    private fun endRecording() {
        armed = false
        recording = null
        answer.setTextSize(TypedValue.COMPLEX_UNIT_PX, answerSize)
        showLastAnswer(demoAnswer ?: LastAnswer.load(this))
        applyNight()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ClaudeVoiceService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private companion object {
        const val REQUEST_MIC = 1
        val DATE = SimpleDateFormat("EEEE d. MMMM", Locale("cs"))
    }
}
