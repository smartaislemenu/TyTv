package com.tytv.app.epg

import android.content.Context
import com.tytv.app.data.HttpUtil
import com.tytv.app.data.Provider
import com.tytv.app.data.ProviderType
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads one provider's guide: resolves the address, streams the XML
 * straight to the provider's cache file (never held whole in memory),
 * then parses it and merges it into [EpgStore].
 *
 * Address resolution: the provider's own EPG address first, then the
 * provider's own xmltv.php for Xtream logins, then the global address
 * from Setup. No address anywhere means no guide for that provider —
 * never a guessed one.
 */
object EpgLoader {

    /** The guide address for a provider, or null when there is none. Pure. */
    fun resolveUrl(provider: Provider, globalUrl: String): String? {
        if (provider.epgUrl.isNotBlank()) return provider.epgUrl
        if (provider.type == ProviderType.XTREAM) {
            val server = provider.server.trim().trimEnd('/')
            if (server.isNotEmpty()) {
                return "$server/xmltv.php?username=${provider.username}&password=${provider.password}"
            }
        }
        if (globalUrl.isNotBlank()) return globalUrl
        return null
    }

    /**
     * Fetches and parses the guide. Returns the parsed data, or null
     * when there is no address or the fetch/parse failed. On failure
     * the previous cache file is left alone so an old guide still shows.
     */
    suspend fun loadForProvider(context: Context, provider: Provider): EpgData? {
        val url = resolveUrl(provider, EpgStore.getGlobalUrl(context)) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val cacheFile = EpgStore.cacheFile(context, provider.name)
                val tmpFile = java.io.File(cacheFile.parent, cacheFile.name + ".tmp")
                val connection = HttpUtil.open(url)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) return@withContext null
                    FileOutputStream(tmpFile).use { tmpOut ->
                        val input = connection.inputStream
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            tmpOut.write(chunk, 0, read)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                // Parse the download BEFORE replacing the cache, so a
                // corrupt download never wipes a good old guide.
                val data = tmpFile.inputStream().use { EpgParser.parse(it) }
                tmpFile.renameTo(cacheFile)
                EpgStore.merge(provider.name, data)
                data
            }
        } catch (e: Exception) {
            null
        }
    }
}
