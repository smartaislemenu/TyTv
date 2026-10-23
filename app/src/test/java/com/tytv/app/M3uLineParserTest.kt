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
}
