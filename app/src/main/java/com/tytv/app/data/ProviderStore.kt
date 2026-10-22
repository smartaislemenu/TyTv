package com.tytv.app.data

import android.content.Context

data class Provider(val name: String, val m3uUrl: String)

/**
 * Providers are stored on-device only (SharedPreferences), one per line
 * as `name|m3uUrl`. Nothing is sent anywhere except the playlist fetch
 * itself, which goes directly to the provider URL the user entered.
 */
object ProviderStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_PROVIDERS = "providers"

    fun getProviders(context: Context): List<Provider> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) Provider(parts[0], parts[1]) else null
        }
    }

    fun addProvider(context: Context, name: String, m3uUrl: String) {
        val updated = getProviders(context) + Provider(name, m3uUrl)
        val raw = updated.joinToString("\n") { "${it.name}|${it.m3uUrl}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDERS, raw).apply()
    }
}
