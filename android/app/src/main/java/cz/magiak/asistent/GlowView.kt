package cz.magiak.asistent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Light along the screen edges: soft while someone speaks, green on the wake word, red on a near miss. */
class GlowView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint()
    private var color = Config.GLOW_COLOR
    private var level = 0f
    private var target = 0f
    private var flashUntil = 0L
    private var animator: ValueAnimator? = null
    private val fade = Runnable { animateTo(0f, 1_200) }

    // Partials arrive many times a second while someone speaks: hold the glow, fade after silence.
    // The next words must not repaint a green or red flash orange before it has been seen.
    fun voice() {
        if (SystemClock.uptimeMillis() < flashUntil) return
        removeCallbacks(fade)
        color = Config.GLOW_COLOR
        if (target < Config.GLOW_VOICE) animateTo(Config.GLOW_VOICE, 200)
        postDelayed(fade, 700)
    }

    fun wake() = flash(Config.GLOW_HIT, 1f)

    fun miss() = flash(Config.GLOW_MISS, Config.GLOW_MISS_LEVEL)

    private fun flash(c: Int, strength: Float) {
        removeCallbacks(fade)
        color = c
        flashUntil = SystemClock.uptimeMillis() + FLASH_MS
        animateTo(strength, 150)
        postDelayed(fade, FLASH_MS)
    }

    fun reset() {
        removeCallbacks(fade)
        flashUntil = 0L
        animator?.cancel()
        level = 0f
        target = 0f
        invalidate()
    }

    private fun animateTo(value: Float, ms: Long) {
        animator?.cancel()
        target = value
        animator = ValueAnimator.ofFloat(level, value).apply {
            duration = ms
            addUpdateListener {
                level = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (level <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val l = paddingLeft.toFloat()
        val t = paddingTop.toFloat()
        val r = w - paddingRight
        val b = h - paddingBottom
        val depth = resources.getDimension(R.dimen.glow_depth)
        val c = color
        val edge = Color.argb((255 * level).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        // Gradients start at the padding (the holder's visible edge); CLAMP fills the hidden strip.
        band(l, 0f, l + depth, 0f, 0f, 0f, l + depth, h)
        band(r, 0f, r - depth, 0f, r - depth, 0f, w, h)
        band(0f, t, 0f, t + depth, 0f, 0f, w, t + depth)
        band(0f, b, 0f, b - depth, 0f, b - depth, w, h)
    }

    private companion object {
        const val FLASH_MS = 1_200L
    }
}
