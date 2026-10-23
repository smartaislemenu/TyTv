package com.tytv.app

import com.tytv.app.parser.M3uLineParser
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uLineParserTest {

    @Test
    fun streamsLinesIntoChannelsIdenticallyToWholeTextParsing() {
        val parser = M3uLineParser("Test Provider")
        parser.accept("#EXTM3U")
        parser.accept("#EXTINF:-1 tvg-name=\"News 24\" group-title=\"News\",News 24 HD")
        parser.accept("http://example.com/news24.ts")
        parser.accept("#EXTINF:-1 group-title=\"Sports\",Sports 1")
        parser.accept("http://example.com/sports1.ts")
        parser.accept("http://example.com/bare-url.ts")
        assertEquals(2, parser.channels.size)
        assertEquals("News 24 HD", parser.channels[0].name)
        assertEquals("News", parser.channels[0].group)
        assertEquals("Sports", parser.channels[1].group)
    }

    @Test
    fun handlesThousandsOfEntriesWithoutHoldingWholeText() {
        val parser = M3uLineParser("Big Provider")
        for (i in 1..5000) {
            parser.accept("#EXTINF:-1 group-title=\"Group ${i % 50}\",Channel $i")
            parser.accept("http://example.com/channel$i.ts")
        }
        assertEquals(5000, parser.channels.size)
        assertEquals("Group 0", parser.channels[4999].group)
    }

    @Test
    fun emptyInputYieldsNoChannels() {
        val parser = M3uLineParser("Empty")
        parser.accept("#EXTM3U")
        assertEquals(0, parser.channels.size)
    }

    @Test
    fun extgrpLinesGroupFollowingEntries() {
        val parser = M3uLineParser("Extgrp Provider")
        parser.accept("#EXTM3U")
        parser.accept("#EXTGRP:USA News")
        parser.accept("#EXTINF:-1,News 24")
        parser.accept("http://example.com/news24.ts")
        parser.accept("#EXTINF:-1,Sports 1")
        parser.accept("http://example.com/sports1.ts")
        parser.accept("#EXTGRP:Movies")
        parser.accept("#EXTINF:-1,Action Flick")
        parser.accept("http://example.com/flick.ts")
        assertEquals(3, parser.channels.size)
        assertEquals("USA News", parser.channels[0].group)
        assertEquals("USA News", parser.channels[1].group)
        assertEquals("Movies", parser.channels[2].group)
    }

    @Test
    fun extinfGroupTitleWinsOverStandingExtgrp() {
        val parser = M3uLineParser("Mixed Provider")
        parser.accept("#EXTGRP:USA News")
        parser.accept("#EXTINF:-1 group-title=\"Kids\",Cartoon Fun")
        parser.accept("http://example.com/cartoon.ts")
        parser.accept("#EXTINF:-1,Plain News")
        parser.accept("http://example.com/plain.ts")
        assertEquals("Kids", parser.channels[0].group)
        assertEquals("USA News", parser.channels[1].group)
    }

    @Test
    fun singleQuotedAttributesParse() {
        val parser = M3uLineParser("Single Quote Provider")
        parser.accept("#EXTINF:-1 tvg-id='cnn.us' tvg-logo='http://example.com/cnn.png' group-title='USA News' tvg-chno='12',CNN")
        parser.accept("http://example.com/cnn.ts")
        val channel = parser.channels[0]
        assertEquals("cnn.us", channel.tvgId)
        assertEquals("http://example.com/cnn.png", channel.logo)
        assertEquals("USA News", channel.group)
        assertEquals(12, channel.number)
    }

    @Test
    fun movieAndSeriesGroupsInferKind() {
        val parser = M3uLineParser("Kind Provider")
        parser.accept("#EXTINF:-1 group-title=\"Movies | 2024\",Action Flick")
        parser.accept("http://example.com/flick.ts")
        parser.accept("#EXTINF:-1 group-title=\"TV Shows\",Drama Series S01E01")
        parser.accept("http://example.com/drama.ts")
        parser.accept("#EXTINF:-1 group-title=\"Showtime\",Showtime East")
        parser.accept("http://example.com/showtime.ts")
        parser.accept("#EXTINF:-1 group-title=\"USA News\",News 24")
        parser.accept("http://example.com/news.ts")
        assertEquals(com.tytv.app.model.ChannelKind.MOVIE, parser.channels[0].kind)
        assertEquals(com.tytv.app.model.ChannelKind.SERIES, parser.channels[1].kind)
        assertEquals(com.tytv.app.model.ChannelKind.LIVE, parser.channels[2].kind)
        assertEquals(com.tytv.app.model.ChannelKind.LIVE, parser.channels[3].kind)
    }

    @Test
    fun catchupAttributesCaptured() {
        val parser = M3uLineParser("Catchup Provider")
        parser.accept("#EXTINF:-1 catchup=\"append\" catchup-days=\"3\",Channel One")
        parser.accept("http://example.com/one.ts")
        parser.accept("#EXTINF:-1 catchup-source=\"http://example.com/catchup?start=\${timestamp}&offset=\${offset}\",Channel Two")
        parser.accept("http://example.com/two.ts")
        assertEquals("append", parser.channels[0].catchupSource)
        assertEquals(72, parser.channels[0].catchupHours)
        assertEquals(
            "http://example.com/catchup?start=\${timestamp}&offset=\${offset}",
            parser.channels[1].catchupSource
        )
    }
}
