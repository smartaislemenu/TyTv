package com.tytv.app.data

import android.content.Context

enum class ProviderType { M3U, XTREAM }

/**
 * One provider the user added. M3U providers carry a playlist URL;
 * Xtream providers carry a server address plus a username and password
 * for that provider's own player_api.php login.
 *
 * Everything is stored on-device only (SharedPreferences). Nothing is
 * sent anywhere except the playlist/API fetch itself, which goes
 * directly to the address the user entered.
 */
data class Provider(
    val name: String,
    val type: ProviderType = ProviderType.M3U,
    val m3uUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = ""
)

object ProviderStore {
    private const val PREFS = "tytv_prefs"
    private const val KEY_PROVIDERS = "providers"

    /**
     * One provider per line. New format: `name|m3u|url` or
     * `name|xtream|server|username|password`. The original format was
     * `name|url` (two parts); it still parses as an M3U provider so
     * providers saved by older builds keep working. Pure, so unit
     * tests can pin the format.
     */
    fun serialize(provider: Provider): String = when (provider.type) {
        ProviderType.M3U -> "${provider.name}|m3u|${provider.m3uUrl}"
        ProviderType.XTREAM ->
            "${provider.name}|xtream|${provider.server}|${provider.username}|${provider.password}"
    }

    /** Parses one stored line, or null when it is not a provider line. Pure. */
    fun parseLine(line: String): Provider? {
        val parts = line.split("|")
        return when {
            parts.size == 2 ->
                Provider(name = parts[0], type = ProviderType.M3U, m3uUrl = parts[1])
            parts.size == 3 && parts[1] == "m3u" ->
                Provider(name = parts[0], type = ProviderType.M3U, m3uUrl = parts[2])
            parts.size == 5 && parts[1] == "xtream" ->
                Provider(
                    name = parts[0],
                    type = ProviderType.XTREAM,
                    server = parts[2],
                    username = parts[3],
                    password = parts[4]
                )
            else -> null
        }
    }

    fun getProviders(context: Context): List<Provider> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { parseLine(it) }
    }

    fun providerByName(context: Context, name: String): Provider? =
        getProviders(context).firstOrNull { it.name == name }

    private fun saveAll(context: Context, providers: List<Provider>) {
        val raw = providers.joinToString("\n") { serialize(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDERS, raw).apply()
    }

    fun addProvider(context: Context, name: String, m3uUrl: String) {
        saveAll(context, getProviders(context) + Provider(name, ProviderType.M3U, m3uUrl = m3uUrl))
    }

    fun addXtreamProvider(
        context: Context,
        name: String,
        server: String,
        username: String,
        password: String
    ) {
        saveAll(
            context,
            getProviders(context) + Provider(
                name,
                ProviderType.XTREAM,
                server = server,
                username = username,
                password = password
            )
        )
    }
}
