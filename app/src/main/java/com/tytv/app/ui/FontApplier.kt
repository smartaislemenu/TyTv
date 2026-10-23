package com.tytv.app.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tytv.app.settings.AppPrefs

/**
 * Applies the user's font choice to a view hierarchy. System font
 * families only (no bundled binaries, so snapshot pushes stay
 * text-only): Default, Condensed, Serif, Monospace. The existing
 * style (bold etc.) is preserved by re-creating the typeface with
 * the view's current style.
 *
 * Applied from the Application lifecycle callback for activity
 * layouts, and from fragment/adapter inflation points for views that
 * are created after the activity's onCreate.
 */
object FontApplier {
    fun apply(view: View) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(AppPrefs.font(view.context).family, style)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) apply(view.getChildAt(i))
        }
    }
}
