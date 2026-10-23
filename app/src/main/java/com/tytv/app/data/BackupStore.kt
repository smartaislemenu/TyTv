package com.tytv.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.tytv.app.settings.AppPrefs
import java.io.File

/**
 * Backup and restore of the app's settings. The backup is a plain
 * text file the user shares and stores themselves, so its contents
 * are their business — with one exception: the parental PIN is
 * never written (a backup file must never unlock parental controls
 * on its own). Everything else goes in, including provider logins,
 * which is why the share sheet carries a warning to keep the file
 * private. Playlist and guide caches are not backed up; they
 * re-download on first launch after a restore.
 *
 * Format, one entry per line:
 *   FILE|<prefs file name>
 *   ENTRY|<type>|<base64 key>|<base64 value>
 * Types: S (string), I (int), L (long), F (float), B (boolean),
 * SET (string set, entries joined with \u0001 before encoding).
 */
object BackupStore {
    const val HEADER = "TYTV-BACKUP-1"
    private const val PREFS_MAIN = "tytv_prefs"
    private const val PREFS_CHANNELS = "tytv_channel_prefs"

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun export(context: Context): String {
        val out = StringBuilder(HEADER).append('\n')
        for (file in listOf(PREFS_MAIN, PREFS_CHANNELS)) {
            out.append("FILE|").append(file).append('\n')
            val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
            for ((key, value) in all) {
                if (file == PREFS_MAIN && key == AppPrefs.KEY_PIN) continue
                val (type, encoded) = when (value) {
                    is String -> "S" to b64(value)
                    is Int -> "I" to b64(value.toString())
                    is Long -> "L" to b64(value.toString())
                    is Float -> "F" to b64(value.toString())
                    is Boolean -> "B" to b64(value.toString())
                    is Set<*> -> "SET" to b64(
                        value.filterIsInstance<String>().joinToString("\u0001")
                    )
                    else -> continue
                }
                out.append("ENTRY|").append(type).append('|')
                    .append(b64(key)).append('|').append(encoded).append('\n')
            }
        }
        return out.toString()
    }

    /** Writes the backup text to a shareable cache file. */
    fun exportFile(context: Context): File {
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        val file = File(dir, "tytv-backup.txt")
        file.writeText(export(context))
        return file
    }

    /**
     * Restores a backup produced by [export]. Returns false when the
     * text is not a backup at all; a partially valid file restores
     * what it can and still returns true.
     */
    fun restore(context: Context, text: String): Boolean {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != HEADER) return false
        var current: SharedPreferences.Editor? = null
        var currentFile: String? = null
        fun flush() {
            current?.apply()
            current = null
            currentFile = null
        }
        for (rawLine in lines.drop(1)) {
            val line = rawLine.trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("FILE|")) {
                flush()
                currentFile = line.removePrefix("FILE|")
                val editor = context.getSharedPreferences(currentFile, Context.MODE_PRIVATE).edit()
                editor.clear()
                current = editor
                continue
            }
            if (!line.startsWith("ENTRY|") || current == null) continue
            val parts = line.split("|", limit = 4)
            if (parts.size != 4) continue
            val key = unb64(parts[2]) ?: continue
            val value = unb64(parts[3]) ?: continue
            val editor = current ?: continue
            when (parts[1]) {
                "S" -> editor.putString(key, value)
                "I" -> value.toIntOrNull()?.let { editor.putInt(key, it) }
                "L" -> value.toLongOrNull()?.let { editor.putLong(key, it) }
                "F" -> value.toFloatOrNull()?.let { editor.putFloat(key, it) }
                "B" -> editor.putBoolean(key, value == "true")
                "SET" -> editor.putStringSet(
                    key,
                    if (value.isEmpty()) emptySet()
                    else value.split("\u0001").toSet()
                )
            }
        }
        flush()
        return true
    }
}
