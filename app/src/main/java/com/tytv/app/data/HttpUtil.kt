package com.tytv.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Shared HTTP plumbing for playlist and Xtream API fetches. All calls
 * run on the caller's background thread; nothing here touches the UI.
 *
 * Timeouts are generous on purpose: a playlist with thousands of
 * channels is several MB, and the old 30-second read timeout is what
 * killed large-playlist loads. The User-Agent looks like an ordinary
 * Android TV browser because some providers reject unknown clients.
 */
object HttpUtil {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 120_000

    /** Opens the URL and returns the connection; the caller reads and closes it. */
    fun open(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    /** Reads the whole response body as text. Throws on HTTP errors and IO failures. */
    fun fetchText(urlString: String): String {
        val connection = open(urlString)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Plain-language reason for a fetch failure, for toasts and logs. Pure. */
    fun failureReason(e: Exception): String = when (e) {
        is SocketTimeoutException ->
            "Timed out — the download took longer than 2 minutes"
        is UnknownHostException ->
            "Couldn't reach the host — check the server address"
        is IOException -> {
            val message = e.message ?: ""
            if (message.startsWith("HTTP ")) "Server returned $message" else "Download failed: $message"
        }
        else -> "Couldn't load: ${e.message ?: "unknown error"}"
    }
}
