package com.tytv.app.parser

import com.tytv.app.model.Channel

/**
 * Minimal M3U playlist parser (pure Kotlin, no Android dependency).
 *
 * Understands `#EXTINF` lines with tvg-name / tvg-logo / group-title
 * attributes followed by the stream URL on the next non-comment line.
 * URLs that appear without a preceding `#EXTINF` line are skipped.
 */
object M3uParser {

    fun parse(content: String, providerName: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingGroup = "Other"
        var pendingLogo: String? = null
        var hasPending = false

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line == "#EXTM3U" -> Unit
                line.startsWith("#EXTINF:") -> {
                    pendingGroup = extractAttr(line, "group-title") ?: "Other"
                    pendingLogo = extractAttr(line, "tvg-logo")
                    val afterComma = line.substringAfter(",", "")
                    pendingName = afterComma.trim().ifEmpty { extractAttr(line, "tvg-name") }
                    hasPending = true
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (hasPending) {
                        channels += Channel(
                            name = pendingName ?: line,
                            url = line,
                            group = pendingGroup,
                            logo = pendingLogo,
                            provider = providerName
                        )
                    }
                    pendingName = null
                    pendingGroup = "Other"
                    pendingLogo = null
                    hasPending = false
                }
            }
        }
        return channels
    }

    private fun extractAttr(line: String, key: String): String? {
        val match = Regex("$key=\"([^\"]*)\"").find(line) ?: return null
        return match.groupValues[1].ifEmpty { null }
    }
}
