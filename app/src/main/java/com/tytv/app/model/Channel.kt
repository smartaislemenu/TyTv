package com.tytv.app.model

/** What kind of item a channel is. Xtream providers supply all three
 *  kinds directly; M3U entries get their kind inferred from the group
 *  name (movie / series groups become MOVIE / SERIES, the rest LIVE). */
enum class ChannelKind { LIVE, MOVIE, SERIES }

/**
 * One item from a provider playlist. Duplicates are kept on purpose:
 * the same channel from two providers shows twice, tagged by provider.
 *
 * [seriesId] is only set for SERIES items from Xtream providers; their
 * [url] is a marker ("xtream:series:<id>"), not a playable stream —
 * tapping one opens its episode list instead of playing directly.
 * M3U series-group entries keep their real stream URL and play
 * directly; the click handler tells the two apart by the URL scheme.
 *
 * [tvgId] is the guide identifier: the M3U tvg-id attribute, or the
 * Xtream epg_channel_id. It is how a channel finds its programmes in
 * an XMLTV guide; without it, matching falls back to the channel name.
 *
 * [number] is the provider's channel number (M3U tvg-chno, Xtream
 * num) when the provider supplies one; null when it doesn't.
 *
 * [catchupSource] describes provider catch-up (watching past
 * programmes) when the provider supports it, and is null otherwise:
 * "xtream" means the Xtream archive URL form (stream URL plus
 * utc/lutc parameters), "append" means the same form on an M3U
 * stream, and any other value is an M3U catchup-source template with
 * ${timestamp} / ${offset} / ${start} / ${end} placeholders.
 * [catchupHours] is how far back catch-up reaches, when known.
 */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val provider: String,
    val kind: ChannelKind = ChannelKind.LIVE,
    val seriesId: String? = null,
    val tvgId: String? = null,
    val number: Int? = null,
    val catchupSource: String? = null,
    val catchupHours: Int? = null
)

/**
 * Builds a catch-up playback URL for a past programme, or null when
 * the channel offers no catch-up or the programme is outside the
 * catch-up window. Pure function, no I/O, so it is unit-testable.
 */
object CatchupUrl {
    fun build(channel: Channel, programmeStartMs: Long, nowMs: Long): String? {
        val source = channel.catchupSource ?: return null
        val windowMs = channel.catchupHours?.let { it * 3_600_000L }
        if (windowMs != null && nowMs - programmeStartMs > windowMs) return null
        if (programmeStartMs > nowMs) return null
        val startSec = programmeStartMs / 1000
        val nowSec = nowMs / 1000
        return when (source) {
            "xtream", "append" -> {
                val sep = if (channel.url.contains("?")) "&" else "?"
                "${channel.url}${sep}utc=$startSec&lutc=$nowSec"
            }
            else -> source
                .replace("\${timestamp}", startSec.toString())
                .replace("\${start}", startSec.toString())
                .replace("\${end}", (programmeStartMs / 1000).toString())
                .replace("\${offset}", (nowSec - startSec).toString())
        }
    }
}

/**
 * Infers a channel's kind from its provider group name, for M3U
 * playlists where every entry would otherwise read as LIVE. Matching
 * is on whole tokens so "Showtime" does not read as a series and
 * "Movie Channel" still reads as a movie.
 */
object GroupKind {
    private val movieTokens = setOf("movie", "movies", "film", "films", "vod")
    private val seriesTokens = setOf("series", "serie", "shows", "show")

    fun infer(group: String): ChannelKind {
        val tokens = group.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return when {
            tokens.any { it in movieTokens } -> ChannelKind.MOVIE
            tokens.any { it in seriesTokens } -> ChannelKind.SERIES
            else -> ChannelKind.LIVE
        }
    }

    /** Broad sports detection for the Sports section: league and
     *  sports-network names, not just groups with "sport" in them.
     *  A channel in Sports still keeps its original Live TV group;
     *  the Sports section is a second view, never a move. */
    private val sportsTokens = setOf(
        "sport", "sports", "espn", "nfl", "nba", "nhl", "mlb", "ncaa",
        "bein", "dazn", "fubo", "supersport", "soccer",
        "football", "basketball", "baseball", "hockey", "golf",
        "tennis", "boxing", "mma", "ufc", "racing", "nascar", "f1"
    )

    fun isSports(channel: Channel): Boolean {
        if (channel.kind != ChannelKind.LIVE) return false
        val tokens = (channel.group + " " + channel.name).lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return tokens.any { it in sportsTokens }
    }
}
