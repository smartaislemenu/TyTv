package com.tytv.app

import com.tytv.app.model.CatchupUrl
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.model.GroupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupKindAndCatchupTest {

    private fun channel(group: String, name: String = "Test") =
        Channel(name = name, url = "http://example.com/x.ts", group = group, logo = null, provider = "P")

    @Test
    fun sportsDetectionCoversLeaguesButNotNews() {
        assertTrue(GroupKind.isSports(channel("NBA")))
        assertTrue(GroupKind.isSports(channel("NFL Network")))
        assertTrue(GroupKind.isSports(channel("USA Sports")))
        assertTrue(GroupKind.isSports(channel("ESPN", "ESPN HD")))
        assertFalse(GroupKind.isSports(channel("Sky News")))
        assertFalse(GroupKind.isSports(channel("USA News")))
        assertFalse(GroupKind.isSports(channel("Movies | 2024")))
    }

    @Test
    fun sportsDetectionDoesNotMoveChannelsOutOfLive() {
        // Sports is a second view: the channel itself stays LIVE.
        val sports = channel("NBA")
        assertEquals(ChannelKind.LIVE, sports.kind)
        assertTrue(GroupKind.isSports(sports))
    }

    @Test
    fun xtreamCatchupBuildsUtcLutcUrl() {
        val channel = channel("USA").copy(
            url = "http://provider.example/live/user/pass/123.ts",
            catchupSource = "xtream",
            catchupHours = 72
        )
        val start = 1_760_000_000_000L
        val now = start + 3_600_000L
        assertEquals(
            "http://provider.example/live/user/pass/123.ts?utc=1760000000&lutc=1760003600",
            CatchupUrl.build(channel, start, now)
        )
    }

    @Test
    fun catchupOutsideWindowReturnsNull() {
        val channel = channel("USA").copy(
            url = "http://provider.example/live/user/pass/123.ts",
            catchupSource = "xtream",
            catchupHours = 24
        )
        val start = 1_760_000_000_000L
        val now = start + 48 * 3_600_000L
        assertNull(CatchupUrl.build(channel, start, now))
    }

    @Test
    fun templateCatchupReplacesPlaceholders() {
        val channel = channel("USA").copy(
            url = "http://example.com/one.ts",
            catchupSource = "http://example.com/c?start=\${timestamp}&off=\${offset}",
            catchupHours = 72
        )
        val start = 1_760_000_000_000L
        val now = start + 600_000L
        assertEquals(
            "http://example.com/c?start=1760000000&off=600",
            CatchupUrl.build(channel, start, now)
        )
    }

    @Test
    fun noCatchupSourceReturnsNull() {
        val channel = channel("USA")
        assertNull(CatchupUrl.build(channel, 1_760_000_000_000L, 1_760_000_600_000L))
    }
}
