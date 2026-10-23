package com.tytv.app.data

import android.content.Context
import android.util.Base64

/**
 * Recently watched live channels, most recent first, capped at 20.
 * Powers the home screen's Recent section and the player's Last
 * button. Stored as Base64 parts so stream URLs (which contain
 * separators) can never corrupt the format. Session-independent:
 * survives restarts, since it lives in SharedPreferences.
 */
object RecentStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_RECENT = "recent_channels"
    private const val KEY_LAST = "last_channel"
    private const val MAX = 20

    data class Recent(
        val provider: String,
        val url: String,
        val name: String,
        val group: String = ""
    )

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun encode(recent: Recent): String =
        listOf(recent.provider, recent.url, recent.name, recent.group)
            .joinToString("|") { b64(it) }

    private fun decode(line: String): Recent? {
        val parts = line.split("|")
        if (parts.size != 4) return null
        val provider = unb64(parts[0]) ?: return null
        val url = unb64(parts[1]) ?: return null
        val name = unb64(parts[2]) ?: return null
        val group = unb64(parts[3]) ?: return null
        return Recent(provider, url, name, group)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, recent: Recent) {
        val current = list(context).filterNot {
            it.provider == recent.provider && it.url == recent.url
        }
        val updated = listOf(recent) + current
        prefs(context).edit()
            .putString(KEY_RECENT, updated.take(MAX).joinToString("\n") { encode(it) })
            .putString(KEY_LAST, encode(recent))
            .apply()
    }

    fun list(context: Context): List<Recent> {
        val raw = prefs(context).getString(KEY_RECENT, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { decode(it) }
    }

    /** (provider, url) pairs in recency order, for the Recent section. */
    fun keys(context: Context): List<Pair<String, String>> =
        list(context).map { it.provider to it.url }

    /** The single most recent channel, for the player's Last button. */
    fun last(context: Context): Recent? {
        val raw = prefs(context).getString(KEY_LAST, "") ?: ""
        if (raw.isBlank()) return null
        return decode(raw)
    }
}
