package com.tytv.app

import com.tytv.app.parser.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-name="News 24" tvg-logo="http://example.com/logo.png" group-title="News",News 24 HD
        http://example.com/news24.m3u8
        #EXTINF:-1 group-title="Sports",Sports 1
        http://example.com/sports1.m3u8
        #EXTINF:-1 tvg-name="News 24" group-title="News",News 24 HD
        http://other.example.com/news24.m3u8
        #EXTGRP:ignored
        http://example.com/bare-url.m3u8
    """.trimIndent()

    @Test
    fun parsesChannelsWithGroupsAndLogos() {
        val channels = M3uParser.parse(sample, "Test Provider")
        assertEquals(3, channels.size)
        assertEquals("News 24 HD", channels[0].name)
        assertEquals("News", channels[0].group)
        assertEquals("http://example.com/logo.png", channels[0].logo)
        assertEquals("Test Provider", channels[0].provider)
        assertEquals("Sports", channels[1].group)
        // Duplicate names from different URLs are kept (provider-tagged).
        assertEquals("News 24 HD", channels[2].name)
        assertEquals("http://other.example.com/news24.m3u8", channels[2].url)
    }

    @Test
    fun bareUrlsWithoutExtinfAreSkipped() {
        val channels = M3uParser.parse(sample, "Test Provider")
        assertEquals(false, channels.any { it.url == "http://example.com/bare-url.m3u8" })
    }

    @Test
    fun emptyPlaylistParsesToEmptyList() {
        assertEquals(0, M3uParser.parse("#EXTM3U\n", "Test Provider").size)
        assertEquals(0, M3uParser.parse("", "Test Provider").size)
    }
}
