package com.tytv.app.epg

import android.content.Context
import com.tytv.app.data.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Background guide preload: runs once per app launch, right after the
 * channel list loads, so the Guide and the player info line already
 * have data by the time the viewer opens them — instead of the Guide
 * sitting empty until its own fetch finishes.
 *
 * Cache files load first (instant), then fresh network loads replace
 * them. Preloading never invents guide data: a provider with no guide
 * address anywhere is recorded in [noAddressProviders] so the Guide
 * can say so plainly instead of showing a blank screen.
 */
object EpgPreload {

    @Volatile private var started = false

    /** Providers with no guide address anywhere. Read-only snapshot. */
    @Volatile var noAddressProviders: Set<String> = emptySet()
        private set

    /** Runs the preload once per process. Returns true if it ran. */
    suspend fun preload(context: Context): Boolean {
        if (started) return false
        started = true
        withContext(Dispatchers.IO) {
            val providers = ProviderStore.getProviders(context)
            val global = EpgStore.getGlobalUrl(context)
            noAddressProviders = providers
                .filter { EpgLoader.resolveUrl(it, global) == null }
                .map { it.name }
                .toSet()
            for (provider in providers) {
                try {
                    val cache = EpgStore.cacheFile(context, provider.name)
                    if (cache.exists()) {
                        val data = cache.inputStream().use { EpgParser.parse(it) }
                        EpgStore.merge(provider.name, data)
                    }
                } catch (e: Exception) {
                    // A bad cache file never blocks the preload.
                }
            }
            providers.map { provider ->
                async { EpgLoader.loadForProvider(context, provider) }
            }.awaitAll()
        }
        return true
    }
}
