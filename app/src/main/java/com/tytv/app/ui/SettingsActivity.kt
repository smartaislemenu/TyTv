package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.BuildConfig
import com.tytv.app.R
import com.tytv.app.data.BackupStore
import com.tytv.app.data.ChannelRepository
import com.tytv.app.data.ReminderStore
import com.tytv.app.epg.EpgStore
import com.tytv.app.settings.AppPrefs
import com.tytv.app.settings.BufferSettings
import com.tytv.app.update.UpdateCheckResult
import com.tytv.app.update.UpdateChecker
import com.tytv.app.update.UpdateDialog
import com.tytv.app.update.UpdateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full settings screen: providers, playback, appearance, guide,
 * parental controls, scores, backup/restore, updates, about. The old
 * Setup popup menu is gone; the sidebar's Setup entry opens this.
 * Every row shows its current value, and rows that open another
 * screen refresh their summary when this screen is revisited.
 */
class SettingsActivity : FragmentActivity() {

    private data class Row(
        val title: String,
        val summary: () -> String,
        val onClick: () -> Unit
    )

    private lateinit var list: RecyclerView
    private var groups: List<Pair<String, String>> = emptyList()
    private val adapter = SettingsAdapter()

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (text != null && BackupStore.restore(this@SettingsActivity, text)) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_restore_done,
                        Toast.LENGTH_LONG
                    ).show()
                    val restart = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(restart)
                    finish()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_restore_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.settings_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        loadGroups()
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(buildRows())
    }

    private fun loadGroups() {
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(this@SettingsActivity, false)
            groups = outcome.channels.map { it.provider to it.group }.distinct()
                .sortedWith(compareBy({ it.first }, { it.second }))
            adapter.submit(buildRows())
        }
    }

    private fun buildRows(): List<Row> {
        val context = this
        return listOf(
            Row(
                getString(R.string.settings_add_provider),
                { getString(R.string.settings_add_provider_summary) },
                { startActivity(Intent(context, AddProviderActivity::class.java)) }
            ),
            Row(
                getString(R.string.settings_buffer),
                {
                    val size = BufferSettings.get(context)
                    "${size.label} (${size.shortDescription})"
                },
                { startActivity(Intent(context, BufferSettingsActivity::class.java)) }
            ),
            Row(
                getString(R.string.settings_player),
                { AppPrefs.playerMode(context).label },
                { showPlayerDialog() }
            ),
            Row(
                getString(R.string.settings_font),
                { AppPrefs.font(context).label },
                { showFontDialog() }
            ),
            Row(
                getString(R.string.settings_global_epg),
                {
                    val url = EpgStore.getGlobalUrl(context)
                    if (url.isBlank()) getString(R.string.settings_not_set) else url
                },
                { showGlobalEpgDialog() }
            ),
            Row(
                getString(R.string.settings_epg_offset),
                {
                    val hours = EpgStore.getTimeOffsetHours(context)
                    if (hours == 0) getString(R.string.settings_none)
                    else if (hours > 0) "+$hours hours" else "$hours hours"
                },
                { showOffsetDialog() }
            ),
            Row(
                getString(R.string.settings_pin),
                {
                    if (AppPrefs.pin(context) == null) getString(R.string.settings_not_set)
                    else getString(R.string.settings_pin_set)
                },
                { showPinRow() }
            ),
            Row(
                getString(R.string.settings_locked_groups),
                {
                    val count = AppPrefs.lockedGroups(context).size
                    if (count == 0) getString(R.string.settings_none_locked)
                    else getString(R.string.settings_groups_locked, count)
                },
                { showLockedGroupsDialog() }
            ),
            Row(
                getString(R.string.settings_scores),
                {
                    if (AppPrefs.scoresEnabled(context)) getString(R.string.settings_on)
                    else getString(R.string.settings_off)
                },
                { showScoresDialog() }
            ),
            Row(
                getString(R.string.settings_reminders),
                {
                    val count = ReminderStore.list(context).size
                    if (count == 0) getString(R.string.settings_no_reminders)
                    else getString(R.string.settings_reminders_set, count)
                },
                { showRemindersDialog() }
            ),
            Row(
                getString(R.string.settings_hidden),
                { getString(R.string.settings_hidden_summary) },
                { startActivity(Intent(context, ManageHiddenActivity::class.java)) }
            ),
            Row(
                getString(R.string.settings_rename_groups),
                { getString(R.string.settings_rename_groups_summary) },
                { startActivity(RenameGroupsActivity.intent(context, groups)) }
            ),
            Row(
                getString(R.string.settings_updates),
                { getString(R.string.settings_updates_summary) },
                { checkForUpdates() }
            ),
            Row(
                getString(R.string.settings_backup),
                { getString(R.string.settings_backup_summary) },
                { shareBackup() }
            ),
            Row(
                getString(R.string.settings_restore),
                { getString(R.string.settings_restore_summary) },
                { restoreLauncher.launch("text/*") }
            ),
            Row(
                getString(R.string.settings_about),
                { "TYTV ${BuildConfig.VERSION_NAME}" },
                { showAbout() }
            )
        )
    }

    private fun showPlayerDialog() {
        val modes = AppPrefs.PlayerMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        val checked = modes.indexOf(AppPrefs.playerMode(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_player)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppPrefs.setPlayerMode(this, modes[which])
                adapter.submit(buildRows())
                dialog.dismiss()
            }
            .show()
    }

    private fun showFontDialog() {
        val fonts = AppPrefs.AppFont.values()
        val labels = fonts.map { it.label }.toTypedArray()
        val checked = fonts.indexOf(AppPrefs.font(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_font)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppPrefs.setFont(this, fonts[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showGlobalEpgDialog() {
        val input = EditText(this).apply {
            setText(EpgStore.getGlobalUrl(this@SettingsActivity))
            hint = getString(R.string.epg_address_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_global_epg)
            .setMessage(R.string.epg_address_note)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                EpgStore.setGlobalUrl(this, input.text.toString().trim())
                adapter.submit(buildRows())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOffsetDialog() {
        val hours = (-12..12).toList()
        val labels = hours.map { h ->
            when {
                h == 0 -> getString(R.string.settings_none)
                h > 0 -> "+$h hours"
                else -> "$h hours"
            }
        }.toTypedArray()
        val checked = hours.indexOf(EpgStore.getTimeOffsetHours(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_epg_offset)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val chosen = hours[which]
                EpgStore.setTimeOffsetHours(this, chosen)
                adapter.submit(buildRows())
                dialog.dismiss()
            }
            .show()
    }

    private fun showPinRow() {
        if (AppPrefs.pin(this) == null) {
            askNewPin()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_pin)
            .setItems(
                arrayOf(
                    getString(R.string.settings_pin_change),
                    getString(R.string.settings_pin_remove)
                )
            ) { _, which ->
                if (which == 0) askNewPin() else {
                    AppPrefs.setPin(this, null)
                    adapter.submit(buildRows())
                }
            }
            .show()
    }

    private fun askNewPin() {
        PinPadDialog.show(this, getString(R.string.settings_pin_new)) { first ->
            PinPadDialog.show(this, getString(R.string.settings_pin_confirm)) { second ->
                if (first == second) {
                    AppPrefs.setPin(this, first)
                    Toast.makeText(this, R.string.settings_pin_saved, Toast.LENGTH_SHORT).show()
                    adapter.submit(buildRows())
                } else {
                    Toast.makeText(this, R.string.settings_pin_mismatch, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLockedGroupsDialog() {
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.settings_no_groups_yet, Toast.LENGTH_LONG).show()
            return
        }
        val locked = AppPrefs.lockedGroups(this).toMutableSet()
        val labels = groups.map { "${it.first} — ${it.second}" }.toTypedArray()
        val checked = groups.map { locked.contains(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_locked_groups)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) locked.add(groups[which]) else locked.remove(groups[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.setLockedGroups(this, locked)
                adapter.submit(buildRows())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemindersDialog() {
        val reminders = ReminderStore.list(this)
        if (reminders.isEmpty()) {
            Toast.makeText(this, R.string.settings_no_reminders, Toast.LENGTH_SHORT).show()
            return
        }
        val timeFmt = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
        val labels = reminders.map { reminder ->
            "${reminder.title} • ${reminder.channelName} • " +
                timeFmt.format(java.util.Date(reminder.startMs)) +
                if (reminder.record) " • REC" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reminders)
            .setMessage(R.string.settings_reminders_delete_hint)
            .setItems(labels) { _, which ->
                val reminder = reminders[which]
                ReminderScheduler.cancel(this, reminder)
                ReminderStore.remove(this, reminder.id)
                Toast.makeText(this, R.string.settings_reminder_deleted, Toast.LENGTH_SHORT)
                    .show()
                adapter.submit(buildRows())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showScoresDialog() {
        val enabled = AppPrefs.scoresEnabled(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_scores)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.settings_on), getString(R.string.settings_off)),
                if (enabled) 0 else 1
            ) { dialog, which ->
                AppPrefs.setScoresEnabled(this, which == 0)
                adapter.submit(buildRows())
                dialog.dismiss()
            }
            .show()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            when (val result = UpdateChecker.check(this@SettingsActivity)) {
                is UpdateCheckResult.Available -> {
                    UpdateDialog.show(this@SettingsActivity, result.info) {
                        UpdateFlow.start(this@SettingsActivity, result.info)
                    }
                }
                is UpdateCheckResult.UpToDate ->
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.update_up_to_date,
                        Toast.LENGTH_SHORT
                    ).show()
                is UpdateCheckResult.Failed ->
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.update_check_failed,
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    private fun shareBackup() {
        try {
            val file = BackupStore.exportFile(this)
            val uri = FileProvider.getUriForFile(
                this, "com.tytv.app.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Toast.makeText(this, R.string.settings_backup_warning, Toast.LENGTH_LONG).show()
            startActivity(Intent.createChooser(send, getString(R.string.settings_backup)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_backup_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_about))
            .setMessage("TYTV ${BuildConfig.VERSION_NAME}\n${getString(R.string.settings_about_text)}")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private inner class SettingsAdapter : RecyclerView.Adapter<SettingsAdapter.Holder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.setting_title)
            val summary: TextView = view.findViewById(R.id.setting_summary)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_setting_row, parent, false)
            FontApplier.apply(view)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.summary.text = row.summary()
            holder.itemView.setOnClickListener { row.onClick() }
        }

        override fun getItemCount(): Int = rows.size
    }
}
