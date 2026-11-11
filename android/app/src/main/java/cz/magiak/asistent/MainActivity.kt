package cz.magiak.asistent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import cz.magiak.asistent.mcp.Connector
import cz.magiak.asistent.meeting.Meeting
import cz.magiak.asistent.meeting.MeetingArchive
import cz.magiak.asistent.meeting.MeetingService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import android.text.format.DateFormat
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
    private lateinit var panel: View
    private lateinit var screenBox: FrameLayout
    private lateinit var screenProblem: TextView
    private var screen: ScreenView? = null
    private var shownRevision = -1
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
    private var resumed = false

    private val launchAfterFlash = Runnable { openClaude() }
    private val listenAgain = Runnable { listener.start() }
    private val hideBarsAgain = Runnable { hideSystemBars() }
    private val everyMinute = object : Runnable {
        override fun run() {
            applyNight()
            date.text = formatDate().replaceFirstChar { it.uppercase() }
            if (!armed && recording == null) showCard()
            updateStatus()
            glow.postDelayed(this, 60_000)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window flag only covers the dashboard. With Claude in front and the cable dropping
        // out, the 60 s timeout ran and the phone woke to the PIN screen (2026-11-08).
        @Suppress("DEPRECATION")
        screenLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "asistent:screen")
            .apply { setReferenceCounted(false); acquire() }
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        glow = findViewById(R.id.glow)
        cardTitle = findViewById(R.id.card_title)
        question = findViewById(R.id.question)
        answer = findViewById(R.id.answer)
        date = findViewById(R.id.date)
        panel = findViewById(R.id.panel)
        screenBox = findViewById(R.id.screen_box)
        screenProblem = findViewById(R.id.screen_problem)
        CustomScreen.onChange = ::showScreen
        answerSize = answer.textSize
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
                // Voice mode would hear the whole meeting and answer it.
                Meeting.state != Meeting.State.IDLE -> {}
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
        Meeting.onChange = ::onMeetingChange
        showDemo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDemo(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Language.current(this) != UserSettings.language(this)) {
            recreate()
            return
        }
        resumed = true
        Theme.apply(this)
        hideSystemBars()
        Session.launchRequestedAt = 0L
        glow.reset()
        showScreen()
        screen?.onResume()
        everyMinute.run()
        refresh()
    }

    // A page that crashed stays off until Claude sends the next one, or it would crash on every return.
    private fun showScreen() {
        if (CustomScreen.revision == shownRevision) return
        shownRevision = CustomScreen.revision
        val html = CustomScreen.load(this) ?: return closeScreen()
        val view = screen ?: ScreenView(this, ::closeScreen).also {
            screen = it
            screenBox.addView(it)
        }
        view.show(html)
        screenBox.visibility = View.VISIBLE
        panel.visibility = View.INVISIBLE
        updateStatus()
    }

    private fun closeScreen() {
        screen?.let {
            screenBox.removeView(it)
            it.destroy()
        }
        screen = null
        screenBox.visibility = View.GONE
        panel.visibility = View.VISIBLE
        updateStatus()
    }

    private fun refresh() {
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                if (askedForMic) {
                    status.text = getString(R.string.mic_denied)
                } else {
                    askedForMic = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
            packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE) == null ->
                status.text = getString(R.string.claude_missing)
            // The meeting service holds the mic and listens for the wake word itself.
            Meeting.recording -> listener.stop()
            !armed && recording == null -> listener.start()
        }
    }

    // Without the service it still listens: Claude opens, only the voice-mode tap is
    // missing. A dashboard that goes deaf looked dead (2026-11-08).
    private fun updateStatus() {
        // Enabled in settings is not running: ASUS blocks the re-bind after a reinstall or reboot
        // unless the app may auto-start, and the setting kept saying "on" all evening (2026-11-08).
        val service = when {
            !isServiceEnabled() -> getString(R.string.service_disabled)
            !Session.serviceConnected -> getString(R.string.service_not_running)
            else -> null
        }
        val note = if (trial) getString(R.string.trial_note) else null
        val connector = if (BuildConfig.CONNECTOR_URL.isNotEmpty() && !Connector.online) getString(R.string.connector_offline) else null
        status.text = listOfNotNull(Session.problem, service, connector, Meeting.note, note, recordNote, listenStatus).joinToString("\n")
        screenProblem.text = listOfNotNull(Session.problem, service, connector).joinToString("\n")
        screenProblem.visibility = if (screen != null && screenProblem.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        resumed = false
        listener.stop()
        recording?.cancel()
        // Armed survives a pause: the USB dialog pops up on every cable wiggle and would
        // silently turn his next tap into opening Claude.
        if (recording != null) endRecording()
        recordNote = null
        glow.removeCallbacks(launchAfterFlash)
        glow.removeCallbacks(listenAgain)
        glow.removeCallbacks(everyMinute)
        screen?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CustomScreen.onChange = null
        Meeting.onChange = null
        screen?.destroy()
        screenLock?.takeIf { it.isHeld }?.release()
        beeper?.release()
        super.onDestroy()
    }

    // Launcher apps must not finish on Back, the dashboard is the home screen.
    @Deprecated("Activity API")
    override fun onBackPressed() {}

    // The flash is the only sign the word was caught, so let it show before Claude covers it.
    private fun onWake(meeting: Boolean) {
        glow.wake()
        beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        if (meeting && !trial) {
            MeetingService.start(this)
            return
        }
        if (trial) {
            glow.postDelayed(listenAgain, 1_200)
            return
        }
        status.text = getString(R.string.hearing_you)
        glow.postDelayed(launchAfterFlash, 600)
    }

    private fun openClaude() {
        listener.stop()
        val launch = packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE)
        if (launch == null) {
            status.text = getString(R.string.claude_missing)
            return
        }
        status.text = getString(R.string.opening_claude)
        Session.launchRequestedAt = System.currentTimeMillis()
        val target = if (UserSettings.newChat(this))
            Intent(Intent.ACTION_VIEW, Uri.parse(Config.NEW_CHAT_URL)).setPackage(Config.CLAUDE_PACKAGE)
        else launch
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onMeetingChange() {
        if (!resumed) return
        refresh()
        showCard()
        updateStatus()
    }

    // Meeting answers leave the card once it ends: the display faces the room for hours.
    private fun showCard() {
        if (Meeting.state == Meeting.State.IDLE) return showLastAnswer(demoAnswer ?: LastAnswer.load(this))
        val min = (System.currentTimeMillis() - Meeting.startedAt) / 60_000
        cardTitle.text = if (Meeting.recording) getString(R.string.meeting_recording, min) else getString(R.string.meeting_summarizing)
        val asked = Meeting.question
        question.visibility = if (asked == null) View.GONE else View.VISIBLE
        question.text = asked
        answer.text = Meeting.answer ?: getString(R.string.meeting_hint, Config.WAKE_LABEL)
    }

    private fun showLastAnswer(last: LastAnswer?) {
        if (last == null || !Config.SHOW_LAST_ANSWER) {
            cardTitle.text = getString(R.string.ask_title)
            question.visibility = View.GONE
            answer.text = getString(R.string.ask_hint, Config.WAKE_LABEL)
            return
        }
        cardTitle.text = getString(R.string.last_title, ago(last.at))
        question.visibility = if (last.question.isBlank()) View.GONE else View.VISIBLE
        question.text = last.question
        answer.text = last.answer
    }

    private fun ago(at: Long): String {
        val min = (System.currentTimeMillis() - at) / 60_000
        return when {
            min < 1 -> getString(R.string.ago_now)
            min < 60 -> getString(R.string.ago_min, min)
            else -> getString(R.string.ago_hours, min / 60)
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

    // Debug builds only. voice/wake/miss/answer: screenshots without anyone speaking.
    // trial: the wake word only flashes, so a round of tries for tuning does not open Claude
    // each time; the Home button ends it.
    // record: a guided recording of his voice for tuning on the PC; starts on his tap.
    // replay: the newest recording through the current Config, no voice needed.
    // screen: a home page tried without Claude, pushed first to
    // /sdcard/Android/data/cz.magiak.asistent/files/screen.html; clock puts the clock back.
    // adb shell am start -n cz.magiak.asistent/.MainActivity --es demo trial
    private fun showDemo(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        // meeting: start or stop without saying it. dry_run: no keys and no sound (offline English
        // transcript, canned answers, voice into files); file + speed play a WAV in instead of the mic.
        when (intent.getStringExtra("meeting")) {
            "start" -> {
                listener.stop()
                MeetingService.start(this, intent.getBooleanExtra("dry_run", false),
                    intent.getStringExtra("file"), intent.getFloatExtra("speed", 1f))
                return
            }
            "stop" -> {
                MeetingService.stop(this)
                return
            }
            // One call to each service with the keys from local.properties (or a test "key"), result in logcat.
            "keys" -> {
                val key = intent.getStringExtra("key")
                Thread({ MeetingService.checkKeys(key) }, "meeting-keys").start()
                return
            }
            // What find_meetings would hand Claude for "query", in logcat.
            "find" -> {
                Log.i(Config.TAG, "Meeting: find\n" + MeetingArchive.find(this, intent.getStringExtra("query").orEmpty()))
                return
            }
        }
        trial = intent.getStringExtra("demo") == "trial"
        // Heard words with their confidence are for tuning only; on the desk they were noise.
        findViewById<View>(R.id.heard).visibility = if (trial) View.VISIBLE else View.GONE
        val show: () -> Unit = when (intent.getStringExtra("demo")) {
            "voice" -> glow::voice
            "wake" -> glow::wake
            "miss" -> glow::miss
            "record" -> {
                arm()
                return
            }
            "screen" -> {
                File(getExternalFilesDir(null), "screen.html").takeIf { it.exists() }
                    ?.let { CustomScreen.save(this, it.readText()) }
                return
            }
            "clock" -> {
                CustomScreen.reset(this)
                return
            }
            "replay" -> {
                val wav = (getExternalFilesDir(null) ?: filesDir)
                    .listFiles { f -> f.name.startsWith("recording-") && f.name.endsWith(".wav") }
                    ?.maxByOrNull { it.name }
                recordNote = getString(if (wav == null) R.string.record_none else R.string.record_evaluating)
                updateStatus()
                if (wav != null) VoiceRecording.replay(this, wav) { recordNote = it; updateStatus() }
                return
            }
            "answer" -> {
                demoAnswer = LastAnswer(
                    getString(R.string.demo_question),
                    getString(R.string.demo_answer),
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
        cardTitle.text = getString(R.string.record_title)
        question.visibility = View.GONE
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        answer.text = getString(R.string.record_arm, Config.WAKE_LABEL)
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
            onLeft = { status.text = getString(R.string.record_left, it) },
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

    private fun formatDate(): String {
        val locale = Locale.getDefault()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date())
    }

    private companion object {
        const val REQUEST_MIC = 1
    }
}
