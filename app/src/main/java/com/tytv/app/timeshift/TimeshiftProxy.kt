package com.tytv.app.timeshift

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny local HTTP server (127.0.0.1 only) that serves a
 * [TimeshiftSession] buffer file to ExoPlayer as if it were a live
 * stream. The player connects to http://127.0.0.1:<port>/stream.ts.
 *
 * - A plain connection serves from the start of the buffer (tune-in).
 * - `?live=1` serves from near the current write position (go-live).
 * - A `Range: bytes=N-` request (what ExoPlayer sends on seek)
 *   serves from byte N, so pause/resume and rewind work through
 *   ordinary seeks. While the session is still recording, the
 *   response has no Content-Length and streams new bytes as they
 *   arrive; when the session finishes, the connection closes.
 *
 * Nothing here leaves the device: the socket binds loopback only.
 */
class TimeshiftProxy(private val session: TimeshiftSession) {

    var port: Int = 0
        private set

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun streamUrl(): String = "http://127.0.0.1:$port/stream.ts"

    fun liveUrl(): String = "http://127.0.0.1:$port/stream.ts?live=1"

    fun start(): Int {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running = true
        thread(name = "tytv-timeshift-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    thread(name = "tytv-timeshift-conn", isDaemon = true) {
                        handle(client)
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
        return port
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        session.stop()
    }

    private fun handle(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            var rangeStart: Long? = null
            val live = requestLine.contains("?live=1")
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val header = line!!
                if (header.startsWith("Range:", ignoreCase = true)) {
                    val value = header.substringAfter("bytes=").substringBefore("-").trim()
                    rangeStart = value.toLongOrNull()
                }
            }
            val output = client.getOutputStream()
            val startPosition = when {
                rangeStart != null -> rangeStart.coerceAtLeast(0)
                live -> maxOf(0L, session.bytesWritten - 1024 * 1024)
                else -> 0L
            }
            val status = if (rangeStart != null || live) {
                "HTTP/1.1 206 Partial Content"
            } else {
                "HTTP/1.1 200 OK"
            }
            val headers = StringBuilder()
                .append(status).append("\r\n")
                .append("Content-Type: video/mp2t\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Connection: close\r\n")
                .append("\r\n")
                .toString()
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.flush()
            serveFrom(output, startPosition)
        } catch (e: Exception) {
            // Client went away or the session ended; just close.
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun serveFrom(output: OutputStream, startPosition: Long) {
        var position = startPosition
        FileInputStream(session.bufferFile).use { input ->
            var skipped = 0L
            while (skipped < position) {
                val n = input.skip(position - skipped)
                if (n <= 0) break
                skipped += n
            }
            position = skipped
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read > 0) {
                    output.write(chunk, 0, read)
                    output.flush()
                    position += read
                } else {
                    if (session.finished) break
                    session.waitForBytes(position, 2000)
                    if (session.finished && position >= session.bytesWritten) break
                }
            }
        }
    }
}
