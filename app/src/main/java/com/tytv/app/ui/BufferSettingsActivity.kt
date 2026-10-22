package com.tytv.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.settings.BufferSize
import com.tytv.app.settings.BufferSettings

/**
 * Buffer-size picker. Four focusable buttons, one per preset; the
 * current preset is marked. Selecting one saves it immediately and the
 * next playback uses the new load-control durations.
 */
class BufferSettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buffer_settings)
        val current = BufferSettings.get(this)
        findViewById<TextView>(R.id.buffer_current).text = "Current: ${current.label}"
        val buttons = mapOf(
            R.id.buffer_low to BufferSize.LOW,
            R.id.buffer_balanced to BufferSize.BALANCED,
            R.id.buffer_high to BufferSize.HIGH,
            R.id.buffer_extra to BufferSize.EXTRA
        )
        for ((buttonId, size) in buttons) {
            val button = findViewById<Button>(buttonId)
            if (size == current) {
                button.text = "✓ ${size.label} — ${size.shortDescription}"
            }
            button.setOnClickListener {
                BufferSettings.set(this, size)
                finish()
            }
        }
    }
}
