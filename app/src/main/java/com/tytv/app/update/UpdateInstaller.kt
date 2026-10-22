package com.tytv.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an update APK into app-private storage and hands it to the
 * system installer. Nothing installs silently: the system install prompt
 * is the user's own confirmation step, and on Android 8+ the user must
 * first allow TYTV to install unknown apps (handled by [openUnknownSourcesSettings]).
 *
 * Signing rule (see README): an update only installs when it is signed
 * with the SAME key as the installed app. A differently-signed APK is
 * rejected by the system installer, not by us.
 */
object UpdateInstaller {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val UPDATE_FILE_NAME = "tytv-update.apk"

    /** True when the system will let this app start an install right now. */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Opens the system "Install unknown apps" toggle for TYTV. */
    fun openUnknownSourcesSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    /**
     * Downloads [apkUrl] to app-private external storage
     * (…/Android/data/com.tytv.app/files/updates/tytv-update.apk).
     * Returns the file, or null on any failure (partial files are deleted).
     * Call off the main thread.
     */
    fun download(context: Context, apkUrl: String): File? {
        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists() && !dir.mkdirs()) return null
        val outFile = File(dir, UPDATE_FILE_NAME)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "TYTV/0.1")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.length() == 0L) {
                outFile.delete()
                return null
            }
            return outFile
        } catch (e: Exception) {
            outFile.delete()
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Fires the system install prompt for [apkFile] via FileProvider.
     * Returns false when no installer could be started.
     */
    fun install(context: Context, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
