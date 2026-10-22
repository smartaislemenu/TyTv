package com.tytv.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of a version check. [Failed] covers every failure mode —
 * no network, HTTP error, malformed document, unknown installed version —
 * so callers never see an exception and never loop on errors.
 */
sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    object Failed : UpdateCheckResult()
}

/**
 * Checks the version descriptor published on the main branch. Runs
 * entirely off the main thread and never throws to the caller, so a
 * failed check can never block or crash app launch.
 *
 * Until the first real release exists, the descriptor URL 404s (or the
 * placeholder APK URL is unreachable). Both cases surface as [Failed],
 * which the silent launch check treats as "no update" and the manual
 * check reports as "couldn't check" — never an error loop or a crash.
 */
object UpdateChecker {

    const val VERSION_URL =
        "https://raw.githubusercontent.com/smartaislemenu/TyTv/main/version.json"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BODY_BYTES = 64 * 1024

    /** Installed version code, or null when it cannot be determined. */
    fun installedVersionCode(context: Context): Int? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            PackageInfoCompat.getLongVersionCode(info).toInt()
        } catch (e: Exception) {
            null
        }
    }

    /** Installed version name for display, or null when it cannot be determined. */
    fun installedVersionName(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        } catch (e: Exception) {
            null
        }
    }

    /** Fetches and compares the descriptor. Never throws. */
    suspend fun check(context: Context): UpdateCheckResult {
        val installed = installedVersionCode(context) ?: return UpdateCheckResult.Failed
        val json = try {
            withContext(Dispatchers.IO) { fetchText(VERSION_URL) }
        } catch (e: Exception) {
            null
        } ?: return UpdateCheckResult.Failed
        val info = VersionInfo.parseVersionJson(json) ?: return UpdateCheckResult.Failed
        return if (VersionInfo.isUpdateAvailable(info.versionCode, installed)) {
            UpdateCheckResult.Available(info)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** GETs a small text document; null on any failure or non-200 status. */
    private fun fetchText(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "TYTV/0.1")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readNBytes(MAX_BODY_BYTES + 1) }
            if (bytes.size > MAX_BODY_BYTES) return null
            return bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
