package com.tytv.app

import com.tytv.app.data.ScoresApi
import com.tytv.app.model.Channel
import com.tytv.app.ui.SurfOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoresAndSurfTest {

    private fun channel(name: String, group: String, provider: String, number: Int? = null) =
        Channel(name = name, url = "http://example.com/$provider/$name", group = group,
            logo = null, provider = provider, number = number)

    @Test
    fun surfOrderGroupsByFirstAppearanceAndSortsByNumber() {
        val channels = listOf(
            channel("B", "Sports", "P1", number = 20),
            channel("A", "Sports", "P1", number = 5),
            channel("C", "News", "P1"),
            channel("D", "Sports", "P1"), // unnumbered: after numbered in its group
            channel("E", "Sports", "P2", number = 1) // new provider group: last
        )
        val ordered = SurfOrder.liveOrder(channels).map { it.name }
        assertEquals(listOf("A", "B", "D", "C", "E"), ordered)
    }

    @Test
    fun surfOrderIsStableForUnnumberedGroups() {
        val channels = listOf(
            channel("X", "G", "P"),
            channel("Y", "G", "P"),
            channel("Z", "G", "P")
        )
        assertEquals(listOf("X", "Y", "Z"), SurfOrder.liveOrder(channels).map { it.name })
    }

    private val sampleFeed = """
        {
          "events": [
            {
              "competitions": [{
                "competitors": [
                  {"homeAway": "away", "score": "102",
                   "team": {"abbreviation": "LAL", "displayName": "Lakers"}},
                  {"homeAway": "home", "score": "98",
                   "team": {"abbreviation": "BOS", "displayName": "Celtics"}}
                ],
                "status": {"type": {"state": "in", "shortDetail": "Q4 2:31"}}
              }]
            },
            {
              "competitions": [{
                "competitors": [
                  {"homeAway": "away", "score": "0",
                   "team": {"abbreviation": "NYG", "displayName": "Giants"}},
                  {"homeAway": "home", "score": "0",
                   "team": {"abbreviation": "DAL", "displayName": "Cowboys"}}
                ],
                "status": {"type": {"state": "pre", "shortDetail": "Sun 4:25 PM ET"}}
              }]
            },
            {
              "competitions": [{
                "competitors": [
                  {"homeAway": "away", "score": "7",
                   "team": {"abbreviation": "KC", "displayName": "Chiefs"}},
                  {"homeAway": "home", "score": "3",
                   "team": {"abbreviation": "BUF", "displayName": "Bills"}}
                ],
                "status": {"type": {"state": "post", "shortDetail": "Final"}}
              }]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun scoresParseKeepsLivePregameAndFinalDistinct() {
        val games = ScoresApi.parse("NBA", sampleFeed)
        assertEquals(3, games.size)

        val live = games[0]
        assertTrue(live.isLive)
        assertEquals("LAL 102 @ BOS 98", live.headline)
        assertEquals("Q4 2:31", live.status)

        val pre = games[1]
        assertEquals("NYG @ DAL", pre.headline) // pregame shows no scores
        assertEquals("Sun 4:25 PM ET", pre.status)

        val final = games[2]
        assertEquals("KC 7 @ BUF 3", final.headline)
        assertEquals("Final", final.status)
    }

    @Test
    fun scoresParseNeverThrowsOnBadInput() {
        assertTrue(ScoresApi.parse("NFL", "").isEmpty())
        assertTrue(ScoresApi.parse("NFL", "not json").isEmpty())
        assertTrue(ScoresApi.parse("NFL", "{}").isEmpty())
        assertTrue(ScoresApi.parse("NFL", """{"events": [{"competitions": []}]}""").isEmpty())
    }
}
