package com.tytv.app.data

import android.content.Context
import com.tytv.app.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Loads every provider's channels and merges them, with the cache as
 * the fast path and the safety net:
 *
 * - Normal load: every provider that has a cache contributes it
 *   immediately; only providers WITHOUT a cache are fetched live.
 *   The home screen shows the result at once instead of waiting on a
 *   multi-MB download.
 * - Forced refresh: every provider is fetched live. A provider whose
 *   fetch fails keeps its old cache in the result (when one exists)
 *   and contributes its failure reason, so one bad provider never
 *   wipes the whole list.
 */
object ChannelRepository {

    data class LoadOutcome(
        val channels: List<Channel>,
        val failures: List<String>,
        /** True when every provider came from its cache (no live fetch). */
        val fromCache: Boolean = false
    )

    suspend fun loadAll(context: Context, forceRefresh: Boolean): LoadOutcome {
        return withContext(Dispatchers.IO) {
            val providers = ProviderStore.getProviders(context)
            if (providers.isEmpty()) return@withContext LoadOutcome(emptyList(), emptyList())

            val jobs = providers.map { provider ->
                async {
                    val cached = PlaylistCache.load(context, provider.name)
                    if (!forceRefresh && cached != null) {
                        return@async Triple(cached, null as String?, true)
                    }
                    val result = when (provider.type) {
                        ProviderType.M3U ->
                            PlaylistLoader.loadM3u(provider.m3uUrl, provider.name)
                        ProviderType.XTREAM ->
                            XtreamClient.fetchAll(
                                provider.server,
                                provider.username,
                                provider.password,
                                provider.name
                            )
                    }
                    when (result) {
                        is PlaylistLoadResult.Success -> {
                            PlaylistCache.save(context, provider.name, result.channels)
                            Triple(result.channels, null, false)
                        }
                        is PlaylistLoadResult.Failure -> {
                            // Keep the old cache for a failed provider.
                            if (cached != null) {
                                Triple(cached, "${provider.name}: ${result.reason}", false)
                            } else {
                                Triple(emptyList<Channel>(), "${provider.name}: ${result.reason}", false)
                            }
                        }
                    }
                }
            }
            val results = jobs.awaitAll()
            val channels = results.flatMap { it.first }
            val failures = results.mapNotNull { it.second }
            val fromCache = !forceRefresh && results.all { it.third }
            LoadOutcome(channels, failures, fromCache)
        }
    }
}
