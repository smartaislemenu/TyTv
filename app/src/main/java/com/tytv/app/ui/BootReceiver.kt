package com.tytv.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tytv.app.data.ReminderStore

/** Re-registers future programme reminders after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val now = System.currentTimeMillis()
        for (reminder in ReminderStore.list(context)) {
            if (reminder.startMs > now) ReminderScheduler.schedule(context, reminder)
        }
    }
}
