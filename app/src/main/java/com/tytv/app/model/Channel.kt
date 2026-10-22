package com.tytv.app.model

/**
 * One channel from a provider playlist. Duplicates are kept on purpose:
 * the same channel from two providers shows twice, tagged by provider.
 */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val provider: String
)
