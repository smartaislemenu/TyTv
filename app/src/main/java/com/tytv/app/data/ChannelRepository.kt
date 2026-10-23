package com.tytv.app.data

import android.content.Context
import com.tytv.app.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
 *
 * Progressive first load: [onUpdate] fires as providers stream in.
 * [LoadUpdate.onProviderProgress] reports live parse counts while a
 * provider downloads, and [LoadUpdate.onProviderDone] fires per
 * provider as its batch finishes, so the UI can render channels as
 * they arrive instead of sitting empty until the slowest provider
 * finishes. Callbacks fire on a background thread; the UI switches
 * to the main thread itself.
 */
object ChannelRepository {

    data class LoadOutcome(
        val channels: List<Channel>,
        val failures: List<String>,
        /** True when every provider came from its cache (no live fetch). */
        val fromCache: Boolean = false
    )

    interface LoadUpdate {
        fun onProviderProgress(provider: String, channelsSoFar: Int)
        fun onProviderDone(provider: String, channels: List<Channel>)
    }

    suspend fun loadAll(
        context: Context,
        forceRefresh: Boolean,
        onUpdate: LoadUpdate? = null
    ): LoadOutcome {
        return withContext(Dispatchers.IO) {
            val providers = ProviderStore.getProviders(context)
            if (providers.isEmpty()) return@withContext LoadOutcome(emptyList(), emptyList())

            val jobs = providers.map { provider ->
                provider to async {
                    val cached = PlaylistCache.load(context, provider.name)
                    if (!forceRefresh && cached != null) {
                        return@async Triple(cached, null as String?, true)
                    }
                    val result = when (provider.type) {
                        ProviderType.M3U ->
                            PlaylistLoader.loadM3u(provider.m3uUrl, provider.name) { count ->
                                onUpdate?.onProviderProgress(provider.name, count)
                            }
                        ProviderType.XTREAM ->
                            XtreamClient.fetchAll(
                                provider.server,
                                provider.username,
                                provider.password,
                                provider.name
                            ) { _, count ->
                                onUpdate?.onProviderProgress(provider.name, count)
                            }
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
            // Emit each provider's batch the moment it finishes, so
            // the UI renders progressively instead of waiting for all.
            jobs.forEach { (provider, deferred) ->
                launch {
                    val result = deferred.await()
                    onUpdate?.onProviderDone(provider.name, result.first)
                }
            }
            val results = jobs.map { it.second.await() }
            val channels = results.flatMap { it.first }
            val failures = results.mapNotNull { it.second }
            val fromCache = !forceRefresh && results.all { it.third }
            LoadOutcome(channels, failures, fromCache)
        }
    }
}
