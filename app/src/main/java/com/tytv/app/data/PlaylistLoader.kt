package com.tytv.app.data

import android.content.Context
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.parser.M3uLineParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one provider load: channels, or a plain-language failure reason. */
sealed class PlaylistLoadResult {
    data class Success(val channels: List<Channel>) : PlaylistLoadResult()
    data class Failure(val reason: String) : PlaylistLoadResult()
}

/**
 * Streams an M3U playlist line by line on a background thread. The old
 * loader read the whole file into one string on a 30-second timeout and
 * parsed it on the main thread — that is what failed on large playlists
 * and froze the UI. This one never holds the whole file, parses as it
 * downloads, reports progress, and names the actual failure.
 */
object PlaylistLoader {

    /**
     * Downloads and parses the playlist. [onCount] fires (on the IO
     * thread) every 2,000 lines and at the end with the channel count
     * found so far, so the UI can show progress.
     */
    suspend fun loadM3u(
        url: String,
        providerName: String,
        onCount: ((Int) -> Unit)? = null
    ): PlaylistLoadResult {
        return try {
            withContext(Dispatchers.IO) {
                val connection = HttpUtil.open(url)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        return@withContext PlaylistLoadResult.Failure("Server returned HTTP $code")
                    }
                    val parser = M3uLineParser(providerName)
                    var lines = 0
                    connection.inputStream.bufferedReader().useLines { sequence ->
                        for (line in sequence) {
                            parser.accept(line)
                            lines++
                            if (lines % 2000 == 0) onCount?.invoke(parser.channels.size)
                        }
                    }
                    onCount?.invoke(parser.channels.size)
                    PlaylistLoadResult.Success(parser.channels)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            PlaylistLoadResult.Failure(HttpUtil.failureReason(e))
        }
    }
}

/**
 * On-device cache of parsed channels, one file per provider. The home
 * screen shows the cache instantly on launch and refreshes in the
 * background, so a relaunch never sits on an empty screen while a
 * multi-MB playlist downloads again.
 *
 * Lines are tab-separated `name\turl\tgroup\tlogo\tkind\tseriesId`
 * with tabs and newlines scrubbed from text fields on write.
 */
object PlaylistCache {
    private const val DIR = "playlist-cache"

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, providerName: String): File {
        val safe = providerName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val hash = providerName.hashCode().let { if (it < 0) -it.toLong() else it.toLong() }
        return File(cacheDir(context), "${safe}_$hash.txt")
    }

    fun save(context: Context, providerName: String, channels: List<Channel>) {
        try {
            val file = fileFor(context, providerName)
            val tmp = File(cacheDir(context), file.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                for (channel in channels) {
                    fun clean(value: String?) =
                        (value ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    out.write(
                        listOf(
                            clean(channel.name),
                            clean(channel.url),
                            clean(channel.group),
                            clean(channel.logo),
                            channel.kind.name,
                            clean(channel.seriesId)
                        ).joinToString("\t")
                    )
                    out.newLine()
                }
            }
            tmp.renameTo(file)
        } catch (e: Exception) {
            // Caching is best-effort; a failed cache write never fails a load.
        }
    }

    fun load(context: Context, providerName: String): List<Channel>? {
        return try {
            val file = fileFor(context, providerName)
            if (!file.exists()) return null
            val channels = mutableListOf<Channel>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split("\t")
                    if (parts.size < 5) continue
                    val kind = try {
                        ChannelKind.valueOf(parts[4])
                    } catch (e: IllegalArgumentException) {
                        ChannelKind.LIVE
                    }
                    channels += Channel(
                        name = parts[0],
                        url = parts[1],
                        group = parts[2],
                        logo = parts[3].ifEmpty { null },
                        provider = providerName,
                        kind = kind,
                        seriesId = parts.getOrNull(5)?.ifEmpty { null }
                    )
                }
            }
            channels
        } catch (e: Exception) {
            null
        }
    }
}
