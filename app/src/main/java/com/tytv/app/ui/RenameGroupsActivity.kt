package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs

/**
 * Lists every provider group from the last browse load. Selecting a
 * group opens its rename form. Labels are re-read from storage on every
 * resume, so returning from the form always shows the fresh label. All
 * buttons are focusable: D-pad up/down moves through the list, select
 * opens a form, back returns to browse.
 */
class RenameGroupsActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_PROVIDERS = "extra_providers"
        private const val EXTRA_GROUPS = "extra_groups"

        fun intent(context: Context, groups: List<Pair<String, String>>): Intent {
            return Intent(context, RenameGroupsActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PROVIDERS, ArrayList(groups.map { it.first }))
                putStringArrayListExtra(EXTRA_GROUPS, ArrayList(groups.map { it.second }))
            }
        }
    }

    private var groups: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rename_groups)
        val providers = intent.getStringArrayListExtra(EXTRA_PROVIDERS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_GROUPS) ?: arrayListOf()
        groups = providers.zip(names)
        findViewById<Button>(R.id.rename_groups_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val list = findViewById<LinearLayout>(R.id.rename_groups_list)
        list.removeAllViews()
        findViewById<TextView>(R.id.rename_groups_empty).visibility =
            if (groups.isEmpty()) View.VISIBLE else View.GONE
        val margin = (16 * resources.displayMetrics.density).toInt()
        for ((provider, group) in groups) {
            val label = ChannelPrefs.getLabel(this, provider, group)
            val display = ChannelPrefs.displayGroup(group, label)
            val button = Button(this).apply {
                text = getString(R.string.rename_group_item, display, provider)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = margin }
                setOnClickListener {
                    startActivity(
                        RenameGroupActivity.intent(
                            this@RenameGroupsActivity,
                            provider,
                            group
                        )
                    )
                }
            }
            list.addView(button)
        }
    }
}
