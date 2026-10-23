package com.tytv.app

import com.tytv.app.data.ChannelPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPrefsTest {

    @Test
    fun keyFormatsAreStable() {
        assertEquals(
            "fav|My Provider|http://example.com/stream.m3u8",
            ChannelPrefs.favoriteKey("My Provider", "http://example.com/stream.m3u8")
        )
        assertEquals(
            "hidden|My Provider|http://example.com/stream.m3u8",
            ChannelPrefs.hiddenKey("My Provider", "http://example.com/stream.m3u8")
        )
        assertEquals(
            "label|My Provider|Sports",
            ChannelPrefs.labelKey("My Provider", "Sports")
        )
    }

    @Test
    fun parseKeyRoundTrips() {
        assertEquals(
            Triple("fav", "My Provider", "http://example.com/stream.m3u8"),
            ChannelPrefs.parseKey("fav|My Provider|http://example.com/stream.m3u8")
        )
        assertEquals(
            Triple("label", "My Provider", "Sports"),
            ChannelPrefs.parseKey("label|My Provider|Sports")
        )
    }

    @Test
    fun parseKeyKeepsPipeInRest() {
        // Rest (a URL or group name) may contain '|'; only the first two
        // separators are structural, so the remainder must stay intact.
        assertEquals(
            Triple("hidden", "Prov", "http://example.com/a|b"),
            ChannelPrefs.parseKey("hidden|Prov|http://example.com/a|b")
        )
    }

    @Test
    fun parseKeyRejectsForeignKeys() {
        assertNull(ChannelPrefs.parseKey("providers"))
        assertNull(ChannelPrefs.parseKey("buffer_size"))
        assertNull(ChannelPrefs.parseKey("other|Prov|Sports"))
        assertNull(ChannelPrefs.parseKey("fav|only-two-parts"))
    }

    @Test
    fun displayGroupKeepsOriginalFirst() {
        assertEquals("Sports", ChannelPrefs.displayGroup("Sports", ""))
        assertEquals("Sports", ChannelPrefs.displayGroup("Sports", "   "))
        assertEquals(
            "Sports • My Sports",
            ChannelPrefs.displayGroup("Sports", "My Sports")
        )
    }
}
