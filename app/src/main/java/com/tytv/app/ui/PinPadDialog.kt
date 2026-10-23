package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tytv.app.R

/**
 * On-screen number pad for the parental PIN, built in code so it
 * works with a D-pad remote (no digit keys needed). Four digits
 * auto-submit; Clear wipes the entry. Used both for setting a new
 * PIN (caller confirms by asking twice) and for verifying it.
 */
object PinPadDialog {

    fun show(context: Context, title: String, onPin: (String) -> Unit) {
        val entered = StringBuilder()
        val dots = TextView(context).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        fun refreshDots() {
            dots.text = (0 until 4).joinToString("  ") { i ->
                if (i < entered.length) "●" else "○"
            }
        }
        refreshDots()

        val grid = GridLayout(context).apply {
            columnCount = 3
            useDefaultMargins = true
        }
        var dialog: AlertDialog? = null
        fun addKey(label: String, onKey: () -> Unit) {
            val button = Button(context).apply {
                text = label
                textSize = 22f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    onKey()
                    refreshDots()
                    if (entered.length == 4) {
                        val pin = entered.toString()
                        dialog?.dismiss()
                        onPin(pin)
                    }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            grid.addView(button, params)
        }
        for (digit in 1..9) addKey(digit.toString()) { entered.append(digit) }
        addKey(context.getString(R.string.pin_clear)) { entered.clear() }
        addKey("0") { entered.append('0') }
        addKey("⌫") { if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(dots)
            addView(grid)
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        // Focus the first digit so D-pad navigation starts on the pad.
        grid.getChildAt(0)?.requestFocus()
    }
}
