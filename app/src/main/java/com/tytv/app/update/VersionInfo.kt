package com.tytv.app.update

/**
 * Pure version-descriptor logic: no Android imports, so it is unit-testable
 * on the JVM. The Android side ([UpdateChecker]) only adds the network fetch
 * and the installed-version lookup.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

object VersionInfo {

    /** True when the remote build is strictly newer than the installed one. */
    fun isUpdateAvailable(remoteVersionCode: Int, installedVersionCode: Int): Boolean =
        remoteVersionCode > installedVersionCode

    /**
     * Parses a version.json document. Returns null when any required field
     * is missing or malformed, so a bad document is a failed check, never
     * a crash. Expected shape:
     * {"versionCode": 2, "versionName": "0.2.0",
     *  "apkUrl": "https://…/tytv.apk", "notes": "…"}
     */
    fun parseVersionJson(json: String): UpdateInfo? {
        val versionCode = extractInt(json, "versionCode") ?: return null
        val versionName = extractString(json, "versionName")?.takeIf { it.isNotBlank() } ?: return null
        val apkUrl = extractString(json, "apkUrl")?.takeIf { it.startsWith("http") } ?: return null
        val notes = extractString(json, "notes") ?: ""
        return UpdateInfo(versionCode, versionName, apkUrl, notes)
    }

    private fun extractInt(json: String, field: String): Int? {
        val match = Regex("\"$field\"\\s*:\\s*(\\d+)").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun extractString(json: String, field: String): String? {
        val match = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return unescape(match.groupValues[1])
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    else -> out.append(raw[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
