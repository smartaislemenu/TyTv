package com.tytv.app.data

import android.content.Context
import android.util.Base64
import java.util.UUID

/**
 * Programme reminders, including scheduled recordings. A reminder
 * fires at the programme's start time (via setAlarmClock, so it
 * survives Doze) and opens an alert with Watch now — and Record
 * when the reminder was set with recording. Recording itself starts
 * only when the viewer confirms at alert time: background recording
 * services need notification permission and foreground-service
 * plumbing that fails silently on many TV boxes, so the reminder
 * (which reliably fires) plus one tap is the honest design.
 *
 * Stored Base64 so titles and URLs can't corrupt the format.
 * Alarms are re-registered after a reboot by BootReceiver.
 */
object ReminderStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_REMINDERS = "reminders"

    data class Reminder(
        val id: String,
        val provider: String,
        val url: String,
        val channelName: String,
        val group: String,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val record: Boolean
    )

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun encode(r: Reminder): String =
        listOf(
            r.id, r.provider, r.url, r.channelName, r.group, r.title,
            r.startMs.toString(), r.endMs.toString(), r.record.toString()
        ).joinToString("|") { b64(it) }

    private fun decode(line: String): Reminder? {
        val parts = line.split("|")
        if (parts.size != 9) return null
        val id = unb64(parts[0]) ?: return null
        val provider = unb64(parts[1]) ?: return null
        val url = unb64(parts[2]) ?: return null
        val channelName = unb64(parts[3]) ?: return null
        val group = unb64(parts[4]) ?: return null
        val title = unb64(parts[5]) ?: return null
        val startMs = unb64(parts[6])?.toLongOrNull() ?: return null
        val endMs = unb64(parts[7])?.toLongOrNull() ?: return null
        val record = unb64(parts[8]) == "true"
        return Reminder(id, provider, url, channelName, group, title, startMs, endMs, record)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY_REMINDERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { decode(it) }.sortedBy { it.startMs }
    }

    fun get(context: Context, id: String): Reminder? =
        list(context).firstOrNull { it.id == id }

    fun add(
        context: Context,
        provider: String,
        url: String,
        channelName: String,
        group: String,
        title: String,
        startMs: Long,
        endMs: Long,
        record: Boolean
    ): Reminder {
        val reminder = Reminder(
            UUID.randomUUID().toString(), provider, url, channelName,
            group, title, startMs, endMs, record
        )
        val updated = list(context) + reminder
        prefs(context).edit()
            .putString(KEY_REMINDERS, updated.joinToString("\n") { encode(it) })
            .apply()
        return reminder
    }

    fun remove(context: Context, id: String) {
        val updated = list(context).filterNot { it.id == id }
        prefs(context).edit()
            .putString(KEY_REMINDERS, updated.joinToString("\n") { encode(it) })
            .apply()
    }
}
