package com.tytv.app.data

import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xtream Codes stream-URL builders. Pure, so unit tests pin the format:
 * live = {server}/live/{user}/{pass}/{id}.ts, movies use the
 * container extension the provider reports, series episodes use the
 * /series/ path with their own extension.
 */
object XtreamUrls {
    fun normalizeServer(server: String): String = server.trim().trimEnd('/')

    fun liveUrl(server: String, user: String, pass: String, streamId: Int): String =
        "${normalizeServer(server)}/live/$user/$pass/$streamId.ts"

    fun movieUrl(server: String, user: String, pass: String, streamId: Int, ext: String): String =
        "${normalizeServer(server)}/movie/$user/$pass/$streamId.${ext.ifEmpty { "mp4" }}"

    fun seriesEpisodeUrl(
        server: String,
        user: String,
        pass: String,
        episodeId: Int,
        ext: String
    ): String =
        "${normalizeServer(server)}/series/$user/$pass/$episodeId.${ext.ifEmpty { "mp4" }}"

    fun apiUrl(server: String, user: String, pass: String, action: String, extra: String = ""): String {
        val base = "${normalizeServer(server)}/player_api.php" +
            "?username=${URLEncoder.encode(user, "UTF-8")}" +
            "&password=${URLEncoder.encode(pass, "UTF-8")}" +
            "&action=$action"
        return if (extra.isEmpty()) base else "$base&$extra"
    }
}

/** One series episode, ready to play. */
data class XtreamEpisode(val name: String, val url: String)

/**
 * Xtream Codes API client (player_api.php). Credentials go only to the
 * provider's own server, on the device's own connection — they are
 * never logged or sent anywhere else.
 *
 * Flow: validate with get_account_info (auth must be 1), then pull
 * live streams, VOD, and series with their categories. Categories
 * become group names, so the home screen groups Xtream items exactly
 * like M3U groups.
 */
object XtreamClient {

    suspend fun fetchAll(
        server: String,
        user: String,
        pass: String,
        providerName: String
    ): PlaylistLoadResult {
        return try {
            withContext(Dispatchers.IO) {
                val account = JSONObject(
                    HttpUtil.fetchText(XtreamUrls.apiUrl(server, user, pass, "get_account_info"))
                )
                val auth = account.optJSONObject("user_info")?.optInt("auth", 0) ?: 0
                if (auth != 1) {
                    return@withContext PlaylistLoadResult.Failure(
                        "Xtream login rejected — check the username and password"
                    )
                }
                val channels = mutableListOf<Channel>()

                val liveCategories = fetchCategories(server, user, pass, "get_live_categories")
                val live = fetchArray(server, user, pass, "get_live_streams")
                for (i in 0 until live.length()) {
                    val item = live.optJSONObject(i) ?: continue
                    val id = item.optInt("stream_id", -1)
                    if (id < 0) continue
                    channels += Channel(
                        name = item.optString("name").ifEmpty { "Channel $id" },
                        url = XtreamUrls.liveUrl(server, user, pass, id),
                        group = liveCategories[item.optString("category_id")] ?: "Live TV",
                        logo = item.optString("stream_icon").ifEmpty { null },
                        provider = providerName,
                        kind = ChannelKind.LIVE
                    )
                }

                val vodCategories = fetchCategories(server, user, pass, "get_vod_categories")
                val vod = fetchArray(server, user, pass, "get_vod_streams")
                for (i in 0 until vod.length()) {
                    val item = vod.optJSONObject(i) ?: continue
                    val id = item.optInt("stream_id", -1)
                    if (id < 0) continue
                    channels += Channel(
                        name = item.optString("name").ifEmpty { "Movie $id" },
                        url = XtreamUrls.movieUrl(
                            server, user, pass, id, item.optString("container_extension")
                        ),
                        group = vodCategories[item.optString("category_id")] ?: "Movies",
                        logo = item.optString("stream_icon").ifEmpty { null },
                        provider = providerName,
                        kind = ChannelKind.MOVIE
                    )
                }

                val seriesCategories = fetchCategories(server, user, pass, "get_series_categories")
                val series = fetchArray(server, user, pass, "get_series")
                for (i in 0 until series.length()) {
                    val item = series.optJSONObject(i) ?: continue
                    val id = item.optString("series_id").ifEmpty { continue }
                    channels += Channel(
                        name = item.optString("name").ifEmpty { "Series $id" },
                        url = "xtream:series:$id",
                        group = seriesCategories[item.optString("category_id")] ?: "Series",
                        logo = item.optString("cover").ifEmpty { null },
                        provider = providerName,
                        kind = ChannelKind.SERIES,
                        seriesId = id
                    )
                }

                PlaylistLoadResult.Success(channels)
            }
        } catch (e: Exception) {
            PlaylistLoadResult.Failure(HttpUtil.failureReason(e))
        }
    }

    /** Episodes for one series, flattened as "S<season> E<episode> — title". */
    suspend fun fetchSeriesEpisodes(
        server: String,
        user: String,
        pass: String,
        seriesId: String
    ): Result<List<XtreamEpisode>> {
        return try {
            withContext(Dispatchers.IO) {
                val info = JSONObject(
                    HttpUtil.fetchText(
                        XtreamUrls.apiUrl(server, user, pass, "get_series_info", "series_id=$seriesId")
                    )
                )
                val episodes = mutableListOf<XtreamEpisode>()
                when (val raw = info.opt("episodes")) {
                    is JSONObject -> {
                        val seasons = raw.keys()
                        while (seasons.hasNext()) {
                            val season = seasons.next()
                            val list = raw.optJSONArray(season) ?: continue
                            for (i in 0 until list.length()) {
                                parseEpisode(server, user, pass, list.optJSONObject(i), season)
                                    ?.let { episodes += it }
                            }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until raw.length()) {
                            val episode = raw.optJSONObject(i) ?: continue
                            parseEpisode(
                                server, user, pass, episode, episode.optString("season")
                            )?.let { episodes += it }
                        }
                    }
                }
                Result.success(episodes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseEpisode(
        server: String,
        user: String,
        pass: String,
        item: JSONObject?,
        season: String
    ): XtreamEpisode? {
        if (item == null) return null
        val id = item.optInt("id", -1)
        if (id < 0) return null
        val episodeNum = item.optInt("episode_num", 0)
        val title = item.optString("title").ifEmpty { "Episode $episodeNum" }
        val name = if (season.isNotEmpty() && episodeNum > 0) {
            "S$season E$episodeNum — $title"
        } else {
            title
        }
        return XtreamEpisode(
            name,
            XtreamUrls.seriesEpisodeUrl(server, user, pass, id, item.optString("container_extension"))
        )
    }

    private fun fetchCategories(
        server: String,
        user: String,
        pass: String,
        action: String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val array = fetchArray(server, user, pass, action)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("category_id")
                val name = item.optString("category_name")
                if (id.isNotEmpty() && name.isNotEmpty()) result[id] = name
            }
        } catch (e: Exception) {
            // A failed category fetch just means groups fall back to defaults.
        }
        return result
    }

    private fun fetchArray(server: String, user: String, pass: String, action: String): JSONArray {
        val text = HttpUtil.fetchText(XtreamUrls.apiUrl(server, user, pass, action))
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            // Some servers wrap the array in an object; treat that as empty.
            JSONArray()
        }
    }
}
