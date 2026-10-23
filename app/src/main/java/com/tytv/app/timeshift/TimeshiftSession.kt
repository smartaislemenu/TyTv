package com.tytv.app.timeshift

import android.os.StatFs
import com.tytv.app.data.HttpUtil
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import kotlin.concurrent.thread

/**
 * Downloads a live TS stream into a growing buffer file while it
 * plays. This is what makes pause, rewind, and record possible on a
 * plain live stream: the player reads from [TimeshiftProxy], which
 * serves this file, so pausing stops the read position, seeking back
 * re-reads older bytes, and the file itself is the recording.
 *
 * Limits are honest: the buffer caps at 2 GB (or when free space
 * drops under 500 MB), and rewind only reaches back to when this
 * session started watching the channel. HLS (.m3u8) and DASH streams
 * are NOT timeshifted — their segmented format needs segment-level
 * handling this version does not do.
 */
class TimeshiftSession(
    private val streamUrl: String,
    val bufferFile: File,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val minFreeBytes: Long = 500L * 1024 * 1024
) {
    @Volatile var bytesWritten: Long = 0
        private set

    @Volatile var finished: Boolean = false
        private set

    /** Set when the download stops early (network error, buffer full). */
    @Volatile var error: String? = null
        private set

    /** When true, the buffer file is kept as a recording on stop. */
    @Volatile var recording: Boolean = false

    private val lock = Object()
    private var connection: HttpURLConnection? = null
    private var downloadThread: Thread? = null

    fun start() {
        downloadThread = thread(name = "tytv-timeshift-download", isDaemon = true) {
            var output: FileOutputStream? = null
            try {
                bufferFile.parentFile?.mkdirs()
                val conn = HttpUtil.open(streamUrl)
                connection = conn
                val code = conn.responseCode
                if (code !in 200..299) {
                    error = "Server returned HTTP $code"
                    return@thread
                }
                output = FileOutputStream(bufferFile)
                val input = conn.inputStream
                val chunk = ByteArray(64 * 1024)
                var sinceSpaceCheck = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    output.write(chunk, 0, read)
                    synchronized(lock) {
                        bytesWritten += read
                        lock.notifyAll()
                    }
                    sinceSpaceCheck += read
                    if (bytesWritten >= maxBytes) {
                        error = "Timeshift buffer full (2 GB)"
                        break
                    }
                    if (sinceSpaceCheck >= 10L * 1024 * 1024) {
                        sinceSpaceCheck = 0
                        val free = try {
                            StatFs(bufferFile.parent).availableBytes
                        } catch (e: Exception) {
                            Long.MAX_VALUE
                        }
                        if (free < minFreeBytes) {
                            error = "Timeshift buffer stopped — low storage"
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (!finished) error = HttpUtil.failureReason(e)
            } finally {
                try {
                    output?.close()
                } catch (e: Exception) {
                }
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            }
        }
    }

    /** Waits until [position] bytes exist or the download finishes. */
    fun waitForBytes(position: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (bytesWritten <= position && !finished) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
            return bytesWritten > position || finished
        }
    }

    fun stop() {
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
        try {
            connection?.disconnect()
        } catch (e: Exception) {
        }
    }
}
