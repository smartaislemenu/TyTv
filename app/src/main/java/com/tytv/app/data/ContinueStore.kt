package com.tytv.app.data

import android.content.Context
import android.util.Base64

/**
 * Continue watching for on-demand streams (movies and series
 * episodes, both of which arrive as MOVIE-kind channels). Live TV
 * has no position to resume, so it is never recorded here.
 *
 * One entry per (provider, url): position and duration in
 * milliseconds, plus the title for display. Entries past 95% (or
 * with no meaningful position) are dropped, so finished items never
 * offer a stale resume. Stored Base64 so URLs can't corrupt the
 * format.
 */
object ContinueStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_CONTINUE = "continue_watching"
    private const val MAX = 30

    data class Entry(
        val provider: String,
        val url: String,
        val name: String,
        val positionMs: Long,
        val durationMs: Long,
        val savedAtMs: Long
    ) {
        /** Fraction watched, 0..1, for the poster progress bar. */
        val fraction: Float
            get() = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
    }

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun encode(entry: Entry): String =
        listOf(
            entry.provider, entry.url, entry.name,
            entry.positionMs.toString(), entry.durationMs.toString(),
            entry.savedAtMs.toString()
        ).joinToString("|") { b64(it) }

    private fun decode(line: String): Entry? {
        val parts = line.split("|")
        if (parts.size != 6) return null
        val provider = unb64(parts[0]) ?: return null
        val url = unb64(parts[1]) ?: return null
        val name = unb64(parts[2]) ?: return null
        val position = unb64(parts[3])?.toLongOrNull() ?: return null
        val duration = unb64(parts[4])?.toLongOrNull() ?: return null
        val savedAt = unb64(parts[5])?.toLongOrNull() ?: return null
        return Entry(provider, url, name, position, duration, savedAt)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_CONTINUE, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { decode(it) }
            .sortedByDescending { it.savedAtMs }
    }

    fun get(context: Context, provider: String, url: String): Entry? =
        list(context).firstOrNull { it.provider == provider && it.url == url }

    /** Saves a position, or drops the entry when finished/meaningless. */
    fun save(
        context: Context,
        provider: String,
        url: String,
        name: String,
        positionMs: Long,
        durationMs: Long
    ) {
        val without = list(context).filterNot {
            it.provider == provider && it.url == url
        }
        val finished = durationMs > 0 && positionMs >= durationMs - 60_000L
        val meaningless = positionMs < 30_000L
        val updated = if (finished || meaningless) {
            without
        } else {
            listOf(
                Entry(provider, url, name, positionMs, durationMs, System.currentTimeMillis())
            ) + without
        }
        prefs(context).edit()
            .putString(KEY_CONTINUE, updated.take(MAX).joinToString("\n") { encode(it) })
            .apply()
    }

    fun clear(context: Context, provider: String, url: String) {
        val without = list(context).filterNot {
            it.provider == provider && it.url == url
        }
        prefs(context).edit()
            .putString(KEY_CONTINUE, without.joinToString("\n") { encode(it) })
            .apply()
    }
}
