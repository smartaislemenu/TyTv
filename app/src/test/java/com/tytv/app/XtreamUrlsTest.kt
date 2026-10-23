package com.tytv.app

import com.tytv.app.data.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlsTest {

    @Test
    fun buildsStandardStreamUrls() {
        assertEquals(
            "http://example.com:8080/live/user1/pass1/123.ts",
            XtreamUrls.liveUrl("http://example.com:8080/", "user1", "pass1", 123)
        )
        assertEquals(
            "http://example.com:8080/movie/user1/pass1/456.mp4",
            XtreamUrls.movieUrl("http://example.com:8080", "user1", "pass1", 456, "mp4")
        )
        assertEquals(
            "http://example.com:8080/series/user1/pass1/789.mkv",
            XtreamUrls.seriesEpisodeUrl("http://example.com:8080/", "user1", "pass1", 789, "mkv")
        )
    }

    @Test
    fun movieUrlDefaultsToMp4WhenExtensionMissing() {
        assertEquals(
            "http://example.com/movie/u/p/1.mp4",
            XtreamUrls.movieUrl("http://example.com", "u", "p", 1, "")
        )
    }

    @Test
    fun apiUrlEncodesCredentials() {
        val url = XtreamUrls.apiUrl("http://example.com", "user name", "p@ss&word", "get_live_streams")
        assertEquals(
            "http://example.com/player_api.php?username=user+name&password=p%40ss%26word&action=get_live_streams",
            url
        )
    }
}
