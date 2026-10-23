package com.tytv.app.epg

/** One programme on one channel, times in epoch milliseconds (device-local rendering). */
data class EpgProgramme(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String
)

/**
 * Parsed XMLTV for one provider: channel ids to display names, and
 * programmes per channel id. Times are absolute, so the guide renders
 * them in the device's own timezone.
 */
data class EpgData(
    val channelNames: Map<String, String>,
    val programmes: Map<String, List<EpgProgramme>>
)
