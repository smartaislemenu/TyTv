package com.tytv.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tytv.app.R
import com.tytv.app.data.ReminderStore

/**
 * Fires when a programme reminder's alarm goes off: opens the alert
 * directly and posts a high-priority notification with a full-screen
 * intent, so the viewer sees it whether or not the direct launch is
 * allowed from the background.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val reminder = ReminderStore.get(context, id) ?: return
        val alert = ReminderAlertActivity.intent(context, id).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(alert)
        } catch (e: Exception) {
            // Background launch refused: the notification below still fires.
        }
        try {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else 0
            val fullScreen = PendingIntent.getActivity(context, id.hashCode() + 1, alert, flags)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(reminder.title)
                .setContentText(reminder.channelName)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (e: Exception) {
            // Notification permission denied: the direct launch above is the fallback.
        }
    }

    companion object {
        const val CHANNEL_ID = "tytv_reminders"
    }
}
