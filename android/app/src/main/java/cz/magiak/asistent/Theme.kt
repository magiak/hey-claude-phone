package cz.magiak.asistent

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView

/** Light or dark dashboard, chosen on the settings screen. Colours live in colors.xml. */
object Theme {

    fun apply(a: Activity) {
        val dark = UserSettings.dark(a)
        fun color(light: Int, darkColor: Int) = a.getColor(if (dark) darkColor else light)
        fun text(id: Int, light: Int, darkColor: Int) = a.findViewById<TextView>(id).setTextColor(color(light, darkColor))

        val surface = color(R.color.surface, R.color.surface_dark)
        a.window.decorView.setBackgroundColor(surface)
        a.findViewById<View>(R.id.root).setBackgroundColor(surface)
        // The card drawable is a shared resource; mutate so the tint stays on this view.
        a.findViewById<View>(R.id.card).background.mutate().setTint(color(R.color.card, R.color.card_dark))
        text(R.id.clock, R.color.clock, R.color.clock_dark)
        text(R.id.date, R.color.muted, R.color.muted_dark)
        text(R.id.status, R.color.status, R.color.status_dark)
        text(R.id.heard, R.color.faint, R.color.faint_dark)
        text(R.id.card_title, R.color.title, R.color.title_dark)
        text(R.id.question, R.color.muted, R.color.muted_dark)
        text(R.id.answer, R.color.text, R.color.text_dark)
        a.findViewById<ImageView>(R.id.settings).setColorFilter(color(R.color.status, R.color.status_dark))

        // Otherwise the strip beside the camera notch stays black next to a light frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            a.window.attributes = a.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
