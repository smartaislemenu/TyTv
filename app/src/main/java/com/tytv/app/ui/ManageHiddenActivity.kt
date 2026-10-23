package com.tytv.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs

/**
 * Lists every hidden channel with an Unhide button. Selecting one brings
 * the channel back to its group row (and to Favorites, if favorited) the
 * next time browse loads. All buttons are focusable: D-pad up/down moves
 * through the list, select unhides, back returns to browse.
 */
class ManageHiddenActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_hidden)
        findViewById<Button>(R.id.hidden_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val hidden = ChannelPrefs.hiddenChannels(this)
        val list = findViewById<LinearLayout>(R.id.hidden_list)
        list.removeAllViews()
        findViewById<TextView>(R.id.hidden_empty).visibility =
            if (hidden.isEmpty()) View.VISIBLE else View.GONE
        val margin = (16 * resources.displayMetrics.density).toInt()
        for (entry in hidden) {
            val button = Button(this).apply {
                text = getString(R.string.unhide_channel, entry.name, entry.provider)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = margin }
                setOnClickListener {
                    ChannelPrefs.unhide(this@ManageHiddenActivity, entry.provider, entry.url)
                    Toast.makeText(
                        this@ManageHiddenActivity,
                        R.string.channel_unhidden,
                        Toast.LENGTH_SHORT
                    ).show()
                    rebuild()
                }
            }
            list.addView(button)
        }
    }
}
