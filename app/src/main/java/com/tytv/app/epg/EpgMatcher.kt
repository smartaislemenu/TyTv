package com.tytv.app.epg

import com.tytv.app.model.Channel

/**
 * Matches TYTV channels to XMLTV guide data. Pure logic, unit-tested.
 *
 * Order: (1) the channel's tvg-id against the XMLTV channel id
 * (this is how Xtream's own xmltv.php lines up, via epg_channel_id);
 * (2) the channel name against XMLTV display names, normalized
 * (lowercased, non-alphanumerics stripped) so "ESPN HD" matches
 * "ESPNHD" and "ESPN HD".
 *
 * Nothing is ever invented: a channel with no match simply shows
 * "No guide data" instead of a guessed listing.
 */
object EpgMatcher {

    /** Normalized name for fuzzy matching. Pure. */
    fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    /** Combined lookup index built from one provider's parsed guide. Pure. */
    data class Index(
        val byId: Map<String, List<EpgProgramme>>,
        val byName: Map<String, List<EpgProgramme>>
    )

    fun buildIndex(data: EpgData): Index {
        val byName = mutableMapOf<String, MutableList<EpgProgramme>>()
        for ((channelId, programmes) in data.programmes) {
            byName.getOrPut(normalize(channelId)) { mutableListOf() } += programmes
            val displayName = data.channelNames[channelId]
            if (!displayName.isNullOrEmpty()) {
                byName.getOrPut(normalize(displayName)) { mutableListOf() } += programmes
            }
        }
        return Index(
            byId = data.programmes,
            byName = byName.mapValues { it.value.sortedBy { programme -> programme.startMs } }
        )
    }

    /** Programmes for one channel across merged indices, or null when unmatched. Pure. */
    fun findFor(channel: Channel, indices: List<Index>): List<EpgProgramme>? {
        val tvgId = channel.tvgId
        if (!tvgId.isNullOrEmpty()) {
            for (index in indices) {
                index.byId[tvgId]?.let { return it }
            }
        }
        val normalized = normalize(channel.name)
        if (normalized.isNotEmpty()) {
            for (index in indices) {
                index.byName[normalized]?.let { return it }
            }
        }
        return null
    }

    /** The programme airing at [nowMs], or null. Pure. */
    fun currentAt(programmes: List<EpgProgramme>, nowMs: Long): EpgProgramme? =
        programmes.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }

    /** The next programme after [nowMs], or null. Pure. */
    fun nextAfter(programmes: List<EpgProgramme>, nowMs: Long): EpgProgramme? =
        programmes.firstOrNull { it.startMs > nowMs }
}
