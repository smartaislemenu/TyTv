package com.tytv.app.parser

import com.tytv.app.model.Channel
import com.tytv.app.model.GroupKind

/**
 * Stateful, line-by-line M3U parser. Feed lines in order with [accept];
 * [channels] grows as complete entries arrive.
 *
 * The streaming playlist loader uses this so a multi-MB playlist is
 * never held in memory as one big string, and [M3uParser] uses it for
 * whole-text parsing (tests and small playlists). Both paths parse
 * identically.
 *
 * Grouping comes from two sources, because providers disagree:
 * `#EXTINF` group-title attributes, and `#EXTGRP:` lines that set the
 * group for every entry that follows until the next `#EXTGRP:` line.
 * An `#EXTINF` group-title wins over a standing `#EXTGRP:` group for
 * that one entry. Attributes accept double- or single-quoted values,
 * since providers use both.
 *
 * Kinds are inferred from the group name (movie / series groups
 * become MOVIE / SERIES, the rest LIVE), so Movies and Series
 * sections actually fill from M3U playlists instead of staying
 * empty. Channel numbers (tvg-chno) and catch-up attributes
 * (catchup / catchup-source / catchup-days) are captured when the
 * provider supplies them. URLs without a preceding `#EXTINF` line
 * are skipped.
 */
class M3uLineParser(private val providerName: String) {

    private var pendingName: String? = null
    private var pendingGroup: String? = null
    private var pendingLogo: String? = null
    private var pendingTvgId: String? = null
    private var pendingNumber: Int? = null
    private var pendingCatchupSource: String? = null
    private var pendingCatchupHours: Int? = null
    private var hasPending = false

    /** Group set by the most recent #EXTGRP line; survives entries. */
    private var extgrpGroup: String? = null

    val channels = mutableListOf<Channel>()

    fun accept(rawLine: String) {
        val line = rawLine.trim()
        when {
            line.isEmpty() -> Unit
            line == "#EXTM3U" -> Unit
            line.startsWith("#EXTINF:") -> {
                pendingGroup = extractAttr(line, "group-title")
                pendingLogo = extractAttr(line, "tvg-logo")
                pendingTvgId = extractAttr(line, "tvg-id")
                pendingNumber = extractAttr(line, "tvg-chno")?.toIntOrNull()
                val catchup = extractAttr(line, "catchup")
                val catchupSource = extractAttr(line, "catchup-source")
                pendingCatchupSource = when {
                    !catchupSource.isNullOrEmpty() -> catchupSource
                    catchup == "append" || catchup == "shift" -> "append"
                    else -> null
                }
                pendingCatchupHours = extractAttr(line, "catchup-days")
                    ?.toIntOrNull()?.let { it * 24 }
                val afterComma = line.substringAfter(",", "")
                pendingName = afterComma.trim().ifEmpty { extractAttr(line, "tvg-name") }
                hasPending = true
            }
            line.startsWith("#EXTGRP:") -> {
                extgrpGroup = line.removePrefix("#EXTGRP:").trim().ifEmpty { null }
            }
            line.startsWith("#") -> Unit
            else -> {
                if (hasPending) {
                    val group = pendingGroup ?: extgrpGroup ?: "Other"
                    channels += Channel(
                        name = pendingName ?: line,
                        url = line,
                        group = group,
                        logo = pendingLogo,
                        provider = providerName,
                        kind = GroupKind.infer(group),
                        tvgId = pendingTvgId,
                        number = pendingNumber,
                        catchupSource = pendingCatchupSource,
                        catchupHours = pendingCatchupHours
                    )
                }
                pendingName = null
                pendingGroup = null
                pendingLogo = null
                pendingTvgId = null
                pendingNumber = null
                pendingCatchupSource = null
                pendingCatchupHours = null
                hasPending = false
            }
        }
    }

    private fun extractAttr(line: String, key: String): String? {
        val doubleQuoted = Regex("$key=\"([^\"]*)\"").find(line)
        if (doubleQuoted != null) return doubleQuoted.groupValues[1].ifEmpty { null }
        val singleQuoted = Regex("$key='([^']*)'").find(line)
        return singleQuoted?.groupValues?.get(1)?.ifEmpty { null }
    }
}
