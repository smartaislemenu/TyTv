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
    private const val DIR = "epg-cache"

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

    /** Programmes for one channel from whatever guides are loaded, or null. */
    fun findFor(channel: Channel): List<EpgProgramme>? {
        val snapshot = indicesSnapshot()
        if (snapshot.isEmpty()) return null
        return EpgMatcher.findFor(channel, snapshot)
    }
}
