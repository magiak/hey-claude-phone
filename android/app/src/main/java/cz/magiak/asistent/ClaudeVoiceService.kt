package cz.magiak.asistent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Clicks the voice-mode button after the dashboard opened Claude, ends a call that went
 * quiet and brings the dashboard back. Does nothing when Claude was opened by hand.
 */
class ClaudeVoiceService : AccessibilityService() {

    private enum class State { IDLE, CLICKING, IN_CALL }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var callStartedAt = 0L
    private var lastChangeAt = 0L
    private var lastScreen = ""
    private var wasSpeaking = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var dumpedLabels = false
    private var clickScheduled = false

    private val watchCall = object : Runnable {
        override fun run() {
            if (state != State.IN_CALL) return
            checkCall()
            if (state == State.IN_CALL) handler.postDelayed(this, 2_000)
        }
    }

    // A login screen or update dialog hides the button; a wall device must not stay stuck there.
    private val giveUp = Runnable {
        if (state != State.CLICKING) return@Runnable
        Log.w(Config.TAG, "Tlačítko hlasového režimu se nenašlo včas.")
        Session.launchRequestedAt = 0L
        Session.problem = "Minule se hlasový režim nespustil. Otevři Clauda a zkontroluj, co ukazuje."
        if (rootInActiveWindow?.packageName == Config.CLAUDE_PACKAGE) backToDashboard() else state = State.IDLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (state == State.IDLE && launchedByDashboard()) {
            state = State.CLICKING
            dumpedLabels = false
            clickScheduled = false
            handler.postDelayed(giveUp, Config.CLICK_TIMEOUT_MS)
        }
        if (state != State.CLICKING) return
        if (event.packageName?.toString() != Config.CLAUDE_PACKAGE) return

        // Someone is tapping in Claude (logging in, say): leave it to them.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.i(Config.TAG, "Někdo kliká v Claudovi, nechávám to na něm.")
            handler.removeCallbacks(giveUp)
            handler.removeCallbacks(clickVoice)
            Session.launchRequestedAt = 0L
            state = State.IDLE
            return
        }

        // A click while Claude is still switching to the new chat could start the call in the
        // previous one, so the first look comes a moment after Claude shows up.
        if (!clickScheduled) {
            clickScheduled = true
            handler.postDelayed(clickVoice, if (UserSettings.newChat(this)) Config.NEW_CHAT_SETTLE_MS else 0L)
        }
    }

    private val clickVoice: Runnable = object : Runnable {
        override fun run() {
            if (state != State.CLICKING) return
            // The dashboard may still own the active window.
            val root = rootInActiveWindow?.takeIf { it.packageName == Config.CLAUDE_PACKAGE }
            val button = root?.let { findButton(it, Config.VOICE_BUTTON) }
            if (button != null && click(button)) {
                Log.i(Config.TAG, "Spouštím hlasový režim.")
                startWatching()
                return
            }
            if (root != null && button == null && !dumpedLabels) logLabels(root)
            handler.postDelayed(this, 500)
        }
    }

    private fun startWatching() {
        handler.removeCallbacks(giveUp)
        Session.launchRequestedAt = 0L
        Session.problem = null
        state = State.IN_CALL
        callStartedAt = System.currentTimeMillis()
        lastChangeAt = callStartedAt
        lastScreen = ""
        wasSpeaking = false
        handler.postDelayed(watchCall, 3_000)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        Session.serviceConnected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Session.serviceConnected = false
        return super.onUnbind(intent)
    }

    private fun launchedByDashboard(): Boolean {
        val at = Session.launchRequestedAt
        return at != 0L && System.currentTimeMillis() - at < Config.CLICK_TIMEOUT_MS
    }

    private fun checkCall() {
        val root = rootInActiveWindow ?: return
        when (root.packageName?.toString()) {
            Config.CLAUDE_PACKAGE -> Unit
            "com.android.systemui" -> return
            // Dashboard is back or he switched apps on purpose: nothing to return to.
            else -> { state = State.IDLE; return }
        }

        // Voice-mode button visible again means he closed the call himself.
        if (findButton(root, Config.VOICE_BUTTON) != null) {
            Log.i(Config.TAG, "Hovor ukončen v aplikaci.")
            rememberAnswer()
            backToDashboard()
            return
        }

        // Only text counts: the animated orb changes the tree all the time, the transcript does not.
        val now = System.currentTimeMillis()
        val screen = walk(root).mapNotNull { it.text?.toString() }.joinToString("\n")
        if (screen != lastScreen) {
            lastScreen = screen
            lastChangeAt = now
        }
        // A long spoken answer adds no text until it ends; Claude's voice plays on the media stream.
        val speaking = audio.isMusicActive
        if (speaking != wasSpeaking) {
            wasSpeaking = speaking
            Log.i(Config.TAG, if (speaking) "Claude mluví." else "Claude domluvil.")
        }
        if (speaking) lastChangeAt = now
        val idleMs = UserSettings.callIdleSeconds(this) * 1_000L
        if (now - lastChangeAt > idleMs || now - callStartedAt > Config.MAX_CALL_MS) {
            Log.i(Config.TAG, "Hovor ztichl, ukončuju ho.")
            findButton(root, Config.STOP_BUTTON)?.let(::click)
            state = State.IDLE
            handler.postDelayed({
                rememberAnswer()
                backToDashboard()
            }, 1_000)
        }
    }

    private fun rememberAnswer() {
        if (!Config.SHOW_LAST_ANSWER) return
        val root = rootInActiveWindow?.takeIf { it.packageName == Config.CLAUDE_PACKAGE } ?: return
        val last = LastAnswer.fromChat(root, walk(root)) ?: return
        Log.i(Config.TAG, "Zapamatovaná odpověď: ${last.answer.length} znaků.")
        LastAnswer.save(this, last)
    }

    private fun backToDashboard() {
        state = State.IDLE
        Log.i(Config.TAG, "Zpátky na nástěnku.")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun findButton(root: AccessibilityNodeInfo, label: Regex): AccessibilityNodeInfo? =
        walk(root).firstOrNull { node ->
            node.isVisibleToUser &&
                listOfNotNull(node.contentDescription, node.text).any { label.matches(it.toString().trim()) }
        }

    // If Claude renames the button, `adb logcat -s Asistent` shows what it offers now.
    private fun logLabels(root: AccessibilityNodeInfo) {
        dumpedLabels = true
        walk(root).forEach {
            val label = listOfNotNull(it.contentDescription, it.text, it.viewIdResourceName).joinToString(" | ")
            if (label.isNotBlank()) Log.i(Config.TAG, "Prvek: $label klikací=${it.isClickable}")
        }
    }

    private fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        repeat(3) {
            if (target?.isClickable == true) return target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target?.parent
        }
        // "Voice Mode" is a label on a non-clickable node (seen 2026-11-08), so tap its centre.
        val bounds = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(tap, null, null)
    }
}
