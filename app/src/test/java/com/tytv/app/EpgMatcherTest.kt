package com.tytv.app

import com.tytv.app.epg.EpgData
import com.tytv.app.epg.EpgMatcher
import com.tytv.app.epg.EpgProgramme
import com.tytv.app.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {

    private fun programme(start: Long, end: Long, title: String) =
        EpgProgramme(start, end, title, "")

    private val data = EpgData(
        channelNames = mapOf("cnn.us" to "CNN", "espn.us" to "ESPN HD"),
        programmes = mapOf(
            "cnn.us" to listOf(
                programme(1000, 2000, "Morning News"),
                programme(2000, 3000, "Midday News")
            ),
            "espn.us" to listOf(
                programme(1000, 3000, "SportsCenter")
            )
        )
    )

    @Test
    fun matchesByTvgIdFirst() {
        val index = EpgMatcher.buildIndex(data)
        val channel = Channel("Whatever", "http://x", "News", null, "P", tvgId = "cnn.us")
        val found = EpgMatcher.findFor(channel, listOf(index))
        assertEquals(2, found?.size)
        assertEquals("Morning News", found?.get(0)?.title)
    }

    @Test
    fun matchesByNormalizedDisplayName() {
        val index = EpgMatcher.buildIndex(data)
        // "ESPNHD" (no space) still matches display name "ESPN HD".
        val channel = Channel("ESPNHD", "http://x", "Sports", null, "P")
        val found = EpgMatcher.findFor(channel, listOf(index))
        assertEquals(1, found?.size)
        assertEquals("SportsCenter", found?.get(0)?.title)
    }

    @Test
    fun unmatchedChannelReturnsNull() {
        val index = EpgMatcher.buildIndex(data)
        val channel = Channel("No Such Channel", "http://x", "Other", null, "P")
        assertNull(EpgMatcher.findFor(channel, listOf(index)))
    }

    @Test
    fun currentAndNextResolve() {
        val programmes = data.programmes.getValue("cnn.us")
        assertEquals("Midday News", EpgMatcher.currentAt(programmes, 2500)?.title)
        assertEquals("Midday News", EpgMatcher.nextAfter(programmes, 1500)?.title)
        assertNull(EpgMatcher.nextAfter(programmes, 3000))
    }
}
