package com.tytv.app.timeshift

import android.content.Context
import java.io.File

/** A saved recording: a TS file in app-private storage, device-only. */
data class Recording(val file: File, val name: String, val sizeBytes: Long)

/**
 * Recordings live in app-private files storage (no permission needed)
 * and never leave the device. [save] moves a finished timeshift buffer
 * into the recordings folder; a rename is instant when possible, with
 * a byte copy as fallback.
 */
object RecordingsStore {
    private const val DIR = "recordings"

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun list(context: Context): List<Recording> {
        val files = dir(context).listFiles { file ->
            file.isFile && file.name.endsWith(".ts", ignoreCase = true)
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { file ->
            Recording(file, file.nameWithoutExtension, file.length())
        }
    }

    fun save(context: Context, bufferFile: File, displayName: String): Boolean {
        return try {
            val safe = displayName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd_HH-mm",
                java.util.Locale.US
            ).format(java.util.Date())
            val target = File(dir(context), "${safe}_$stamp.ts")
            if (bufferFile.renameTo(target)) return true
            bufferFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            bufferFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(recording: Recording): Boolean = recording.file.delete()
}
