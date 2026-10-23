package com.tytv.app.model

/** What kind of item a channel is. M3U entries are always LIVE; Xtream
 *  providers also supply MOVIE and SERIES items. */
enum class ChannelKind { LIVE, MOVIE, SERIES }

/**
 * One item from a provider playlist. Duplicates are kept on purpose:
 * the same channel from two providers shows twice, tagged by provider.
 *
 * [seriesId] is only set for SERIES items from Xtream providers; their
 * [url] is a marker ("xtream:series:<id>"), not a playable stream —
 * tapping one opens its episode list instead of playing directly.
 *
 * [tvgId] is the guide identifier: the M3U tvg-id attribute, or the
 * Xtream epg_channel_id. It is how a channel finds its programmes in
 * an XMLTV guide; without it, matching falls back to the channel name.
 */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val provider: String,
    val kind: ChannelKind = ChannelKind.LIVE,
    val seriesId: String? = null,
    val tvgId: String? = null
)
