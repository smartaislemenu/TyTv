package com.tytv.app.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live scores from ESPN's public scoreboard feeds — no key, no
 * account, major leagues only. Best-effort by design: a league that
 * fails to load simply contributes nothing, and scores are never
 * linked to channels (no provider supplies scores, and nothing here
 * is faked — every row comes from the feed).
 *
 * The feed is fetched on demand with a short timeout and cached in
 * memory for 60 seconds, so the Sports section and the Scores
 * screen share one fetch instead of hammering the feed.
 */
object ScoresApi {

    data class Game(
        val league: String,
        val away: String,
        val home: String,
        val awayScore: String,
        val homeScore: String,
        val status: String,
        val isLive: Boolean
    ) {
        /** "AWAY 98 @ HOME 102" (scores omitted before kickoff). */
        val headline: String
            get() = if (awayScore.isEmpty() && homeScore.isEmpty()) {
                "$away @ $home"
            } else {
                "$away $awayScore @ $home $homeScore"
            }
    }

    private data class League(val label: String, val path: String)

    private val leagues = listOf(
        League("NFL", "football/nfl"),
        League("NBA", "basketball/nba"),
        League("MLB", "baseball/mlb"),
        League("NHL", "hockey/nhl"),
        League("College Football", "football/college-football"),
        League("College Basketball", "basketball/mens-college-basketball")
    )

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val CACHE_TTL_MS = 60_000L

    @Volatile private var cachedAtMs = 0L
    @Volatile private var cached: List<Game> = emptyList()

    /** All leagues' games, from cache when fresh. Never throws. */
    suspend fun fetchAll(): List<Game> {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS && cachedAtMs > 0) return cached
        val games = withContext(Dispatchers.IO) {
            leagues.map { league ->
                async { fetchLeague(league) }
            }.awaitAll().flatten()
        }
        cachedAtMs = now
        cached = games
        return games
    }

    /** Live games only, for the Sports section's scores strip. */
    suspend fun fetchLive(): List<Game> = fetchAll().filter { it.isLive }

    private fun fetchLeague(league: League): List<Game> {
        return try {
            val text = fetchText("$BASE/${league.path}/scoreboard")
            parse(league.label, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        try {
            if (connection.responseCode !in 200..299) return ""
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Parses one scoreboard feed. Pure, so unit tests can pin it. */
    fun parse(league: String, text: String): List<Game> {
        if (text.isBlank()) return emptyList()
        val games = mutableListOf<Game>()
        try {
            val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val competition = event.optJSONArray("competitions")?.optJSONObject(0)
                    ?: continue
                val competitors = competition.optJSONArray("competitors") ?: continue
                var awayName = ""
                var homeName = ""
                var awayScore = ""
                var homeScore = ""
                for (j in 0 until competitors.length()) {
                    val competitor = competitors.optJSONObject(j) ?: continue
                    val team = competitor.optJSONObject("team") ?: continue
                    val name = team.optString("abbreviation").ifEmpty {
                        team.optString("shortDisplayName").ifEmpty {
                            team.optString("displayName")
                        }
                    }
                    val score = competitor.optString("score", "")
                    when (competitor.optString("homeAway")) {
                        "away" -> {
                            awayName = name
                            awayScore = score
                        }
                        "home" -> {
                            homeName = name
                            homeScore = score
                        }
                    }
                }
                if (awayName.isEmpty() || homeName.isEmpty()) continue
                val status = competition.optJSONObject("status")
                val type = status?.optJSONObject("type")
                val state = type?.optString("state") ?: ""
                val shortDetail = type?.optString("shortDetail") ?: ""
                val statusText = when (state) {
                    "in" -> shortDetail.ifEmpty {
                        status?.optString("displayClock")?.ifEmpty { "Live" } ?: "Live"
                    }
                    "post" -> "Final"
                    else -> shortDetail.ifEmpty { "TBD" }
                }
                games += Game(
                    league = league,
                    away = awayName,
                    home = homeName,
                    awayScore = if (state == "pre") "" else awayScore,
                    homeScore = if (state == "pre") "" else homeScore,
                    status = statusText,
                    isLive = state == "in"
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return games
    }
}
