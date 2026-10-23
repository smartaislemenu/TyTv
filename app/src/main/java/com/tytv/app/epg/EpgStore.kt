package com.tytv.app.epg

import android.content.Context
import com.tytv.app.model.Channel
import java.io.File

/**
 * EPG storage: a global fallback guide address (Setup), one raw-XML
 * cache file per provider, and the merged in-memory lookup index.
 * Guide data never leaves the device except for the fetch itself,
 * which goes directly to the address the user entered (or the
 * provider's own xmltv.php for Xtream).
 */
object EpgStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_GLOBAL_URL = "epg_global_url"
    private const val KEY_TIME_OFFSET_HOURS = "epg_time_offset_hours"
    private const val DIR = "epg-cache"

    /**
     * Shift applied to displayed programme times, in milliseconds.
     * Some providers publish guide times off by a fixed number of
     * hours; the offset (set in Settings, may be negative) corrects
     * them at lookup time. The stored guide data stays pristine.
     */
    @Volatile var timeOffsetMs: Long = 0L

    fun getTimeOffsetHours(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TIME_OFFSET_HOURS, 0)

    fun setTimeOffsetHours(context: Context, hours: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TIME_OFFSET_HOURS, hours).apply()
        timeOffsetMs = hours * 3_600_000L
    }

    private val lock = Object()
    private val indices = mutableMapOf<String, EpgMatcher.Index>()

    fun getGlobalUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_URL, "") ?: ""

    fun setGlobalUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GLOBAL_URL, url).apply()
    }

    fun cacheFile(context: Context, providerName: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safe = providerName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val hash = providerName.hashCode().let { if (it < 0) -it.toLong() else it.toLong() }
        return File(dir, "${safe}_$hash.xml")
    }

    /**
     * Puts one provider's parsed guide into the in-memory index,
     * replacing any older guide from the same provider — so a fresh
     * fetch always wins over a stale cache.
     */
    fun merge(providerName: String, data: EpgData) {
        synchronized(lock) {
            indices[providerName] = EpgMatcher.buildIndex(data)
        }
    }

    fun clearMemory() {
        synchronized(lock) {
            indices.clear()
        }
    }

    /** Snapshot of the merged indices (empty until a guide loads). */
    fun indicesSnapshot(): List<EpgMatcher.Index> {
        synchronized(lock) {
            return indices.values.toList()
        }
    }

    /** Programmes for one channel from whatever guides are loaded, or null.
     *  Returned times carry the configured time offset, so every
     *  consumer (guide, player info, home rows) sees corrected times
     *  without any call-site changes. */
    fun findFor(channel: Channel): List<EpgProgramme>? {
        val snapshot = indicesSnapshot()
        if (snapshot.isEmpty()) return null
        val programmes = EpgMatcher.findFor(channel, snapshot) ?: return null
        val offset = timeOffsetMs
        if (offset == 0L) return programmes
        return programmes.map {
            it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset)
        }
    }
}
