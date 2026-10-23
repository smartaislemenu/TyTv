package com.tytv.app.parser

import com.tytv.app.model.Channel

/**
 * Stateful, line-by-line M3U parser. Feed lines in order with [accept];
 * [channels] grows as complete entries arrive.
 *
 * The streaming playlist loader uses this so a multi-MB playlist is
 * never held in memory as one big string, and [M3uParser] uses it for
 * whole-text parsing (tests and small playlists). Both paths parse
 * identically: `#EXTINF` lines with tvg-name / tvg-logo / group-title
 * attributes followed by the stream URL on the next non-comment line.
 * URLs without a preceding `#EXTINF` line are skipped.
 */
class M3uLineParser(private val providerName: String) {

    private var pendingName: String? = null
    private var pendingGroup = "Other"
    private var pendingLogo: String? = null
    private var pendingTvgId: String? = null
    private var hasPending = false

    val channels = mutableListOf<Channel>()

    fun accept(rawLine: String) {
        val line = rawLine.trim()
        when {
            line.isEmpty() -> Unit
            line == "#EXTM3U" -> Unit
            line.startsWith("#EXTINF:") -> {
                pendingGroup = extractAttr(line, "group-title") ?: "Other"
                pendingLogo = extractAttr(line, "tvg-logo")
                pendingTvgId = extractAttr(line, "tvg-id")
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
                        provider = providerName,
                        tvgId = pendingTvgId
                    )
                }
                pendingName = null
                pendingGroup = "Other"
                pendingLogo = null
                pendingTvgId = null
                hasPending = false
            }
        }
    }

    private fun extractAttr(line: String, key: String): String? {
        val match = Regex("$key=\"([^\"]*)\"").find(line) ?: return null
        return match.groupValues[1].ifEmpty { null }
    }
}
