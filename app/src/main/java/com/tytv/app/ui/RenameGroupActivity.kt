package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs

/**
 * Rename form for one provider group. The field starts with the current
 * label (blank when there is none). Saving stores the label; "Use
 * original only" clears it. Either way the provider's original group name
 * stays visible next to the label in the browse rows — it is never
 * replaced. All fields and buttons are focusable, so the screen works
 * with a TV remote: arrows move focus, select opens the on-screen
 * keyboard for the field or activates a button.
 */
class RenameGroupActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_PROVIDER = "extra_provider"
        private const val EXTRA_GROUP = "extra_group"

        fun intent(context: Context, provider: String, group: String): Intent {
            return Intent(context, RenameGroupActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER, provider)
                putExtra(EXTRA_GROUP, group)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rename_group)
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: ""
        val group = intent.getStringExtra(EXTRA_GROUP) ?: ""
        findViewById<TextView>(R.id.rename_group_info).text =
            getString(R.string.rename_group_info, group, provider)
        val labelInput = findViewById<EditText>(R.id.rename_group_input)
        labelInput.setText(ChannelPrefs.getLabel(this, provider, group))
        findViewById<Button>(R.id.rename_group_save).setOnClickListener {
            val label = labelInput.text.toString().trim()
            if (label.isEmpty()) {
                Toast.makeText(this, R.string.rename_group_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ChannelPrefs.setLabel(this, provider, group, label)
            Toast.makeText(this, R.string.rename_group_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.rename_group_clear).setOnClickListener {
            ChannelPrefs.setLabel(this, provider, group, "")
            Toast.makeText(this, R.string.rename_group_cleared, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
