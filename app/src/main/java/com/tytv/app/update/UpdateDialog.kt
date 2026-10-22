package com.tytv.app.update

import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R

/**
 * D-pad-friendly update prompt. Uses the framework AlertDialog (not the
 * AppCompat one, which requires an AppCompat theme this Leanback app
 * does not use). Dialog buttons are focusable by default: arrows move
 * between Update/Later, select activates, back dismisses.
 */
object UpdateDialog {

    fun show(activity: FragmentActivity, info: UpdateInfo, onUpdate: () -> Unit) {
        val message = if (info.notes.isBlank()) {
            activity.getString(R.string.update_available, info.versionName)
        } else {
            activity.getString(R.string.update_available_notes, info.versionName, info.notes)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, info.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> onUpdate() }
            .setNegativeButton(R.string.update_later, null)
            .setCancelable(true)
            .create()
        dialog.show()
        // Start focus on Update so a single select press accepts.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }
}
