package com.tytv.app

import com.tytv.app.data.Provider
import com.tytv.app.data.ProviderStore
import com.tytv.app.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderLineTest {

    @Test
    fun m3uRoundTrips() {
        val provider = Provider("My List", ProviderType.M3U, m3uUrl = "http://example.com/list.m3u")
        assertEquals(provider, ProviderStore.parseLine(ProviderStore.serialize(provider)))
    }

    @Test
    fun xtreamRoundTrips() {
        val provider = Provider(
            "My Xtream", ProviderType.XTREAM,
            server = "http://example.com:8080", username = "user1", password = "pass1"
        )
        assertEquals(provider, ProviderStore.parseLine(ProviderStore.serialize(provider)))
    }

    @Test
    fun legacyTwoPartLinesStillParseAsM3u() {
        val parsed = ProviderStore.parseLine("Old List|http://example.com/old.m3u")
        assertEquals("Old List", parsed?.name)
        assertEquals(ProviderType.M3U, parsed?.type)
        assertEquals("http://example.com/old.m3u", parsed?.m3uUrl)
    }

    @Test
    fun garbageLinesReturnNull() {
        assertNull(ProviderStore.parseLine("not a provider line"))
        assertNull(ProviderStore.parseLine("a|b|c"))
    }
}
