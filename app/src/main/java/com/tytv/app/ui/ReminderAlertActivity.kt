package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ReminderStore
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The reminder alert: fires at the programme's start time with Watch
 * now, and Record when the reminder was set with recording (which
 * opens the player with recording armed). The reminder is removed
 * as soon as the alert shows, so it never fires twice.
 */
class ReminderAlertActivity : FragmentActivity() {

    companion object {
        fun intent(context: Context, reminderId: String): Intent {
            return Intent(context, ReminderAlertActivity::class.java).apply {
                putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_alert)
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
        val reminder = id?.let { ReminderStore.get(this, it) }
        if (reminder == null) {
            finish()
            return
        }
        ReminderStore.remove(this, id)
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        findViewById<TextView>(R.id.reminder_title).text = reminder.title
        findViewById<TextView>(R.id.reminder_channel).text = reminder.channelName
        findViewById<TextView>(R.id.reminder_time).text =
            "${fmt.format(java.util.Date(reminder.startMs))} – " +
                fmt.format(java.util.Date(reminder.endMs))
        val channel = Channel(
            name = reminder.channelName,
            url = reminder.url,
            group = reminder.group,
            logo = null,
            provider = reminder.provider,
            kind = ChannelKind.LIVE
        )
        findViewById<Button>(R.id.reminder_watch).setOnClickListener {
            ChannelMenu.play(this, channel)
            finish()
        }
        val recordButton = findViewById<Button>(R.id.reminder_record)
        if (reminder.record) {
            recordButton.visibility = View.VISIBLE
            recordButton.setOnClickListener {
                val play = PlaybackActivity.intent(this, channel).apply {
                    putExtra(PlaybackActivity.EXTRA_AUTO_RECORD, true)
                }
                startActivity(play)
                finish()
            }
        }
        findViewById<Button>(R.id.reminder_dismiss).setOnClickListener { finish() }
    }
}
