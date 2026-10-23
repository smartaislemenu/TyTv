package com.tytv.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ReminderStore
import com.tytv.app.epg.EpgProgramme
import com.tytv.app.model.Channel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Schedules programme reminders with setAlarmClock: it fires at the
 * exact start time even in Doze, and needs no exact-alarm permission.
 * The alarm's broadcast posts a notification (with a full-screen
 * intent) and also tries to open the alert directly, so the viewer
 * sees it either way.
 */
object ReminderScheduler {
    const val EXTRA_REMINDER_ID = "reminder_id"

    private fun alertIntent(context: Context, id: String): Intent =
        ReminderAlertActivity.intent(context, id)

    private fun receiverIntent(context: Context, id: String): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, id)
        }

    fun schedule(context: Context, reminder: ReminderStore.Reminder) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else 0
        val show = PendingIntent.getActivity(
            context, reminder.id.hashCode() + 1, alertIntent(context, reminder.id), flags
        )
        val operation = PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), receiverIntent(context, reminder.id), flags
        )
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(reminder.startMs, show),
            operation
        )
    }

    fun cancel(context: Context, reminder: ReminderStore.Reminder) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else 0
        manager.cancel(
            PendingIntent.getBroadcast(
                context, reminder.id.hashCode(), receiverIntent(context, reminder.id), flags
            )
        )
    }

    /**
     * Asks for the notification permission on Android 13+ so the
     * reminder notification can show. Scheduling proceeds regardless:
     * the alarm itself does not depend on it.
     */
    fun ensureNotificationPermission(activity: FragmentActivity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }

    /** Sets a reminder (optionally with recording) and schedules it. */
    fun setReminder(
        activity: FragmentActivity,
        channel: Channel,
        programme: EpgProgramme,
        record: Boolean
    ) {
        ensureNotificationPermission(activity)
        val reminder = ReminderStore.add(
            activity, channel.provider, channel.url, channel.name, channel.group,
            programme.title, programme.startMs, programme.endMs, record
        )
        schedule(activity, reminder)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault())
            .format(java.util.Date(programme.startMs))
        Toast.makeText(
            activity,
            activity.getString(
                if (record) R.string.reminder_record_set else R.string.reminder_set,
                programme.title, time
            ),
            Toast.LENGTH_LONG
        ).show()
    }
}
