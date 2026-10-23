package com.tytv.app.data

import android.content.Context
import com.tytv.app.model.Channel

/** A channel the user hid: provider + stream URL identify it, name is display-only. */
data class HiddenChannel(val provider: String, val url: String, val name: String)

/**
 * Favorites, hidden channels, and group renames. Everything lives in one
 * on-device SharedPreferences file — no playlist, login, or credential is
 * ever stored here, and nothing is sent anywhere.
 *
 * Keys are `kind|provider|rest`, where rest is a stream URL (favorites /
 * hidden) or the provider's original group name (renames). Provider names
 * never contain '|' (AddProviderActivity rejects it); rest may, so parsing
 * always splits with a limit of 3 and keeps the remainder intact.
 */
object ChannelPrefs {
    private const val PREFS = "tytv_channel_prefs"
    private const val KIND_FAVORITE = "fav"
    private const val KIND_HIDDEN = "hidden"
    private const val KIND_LABEL = "label"

    /** Preference key for a favorite. Pure, so unit tests can pin the format. */
    fun favoriteKey(provider: String, url: String): String =
        "$KIND_FAVORITE|$provider|$url"

    /** Preference key for a hidden channel. Pure. */
    fun hiddenKey(provider: String, url: String): String =
        "$KIND_HIDDEN|$provider|$url"

    /** Preference key for a group rename. Pure. */
    fun labelKey(provider: String, group: String): String =
        "$KIND_LABEL|$provider|$group"

    /**
     * Parses a ChannelPrefs key into (kind, provider, rest), or null when the
     * key is not one of ours. Pure.
     */
    fun parseKey(key: String): Triple<String, String, String>? {
        val parts = key.split("|", limit = 3)
        if (parts.size != 3) return null
        val (kind, provider, rest) = parts
        if (kind != KIND_FAVORITE && kind != KIND_HIDDEN && kind != KIND_LABEL) return null
        return Triple(kind, provider, rest)
    }

    /**
     * What a group row is called on screen: the provider's ORIGINAL name
     * first, with the user's label alongside it. A blank label means the
     * original name alone — the original is never replaced. Pure.
     */
    fun displayGroup(original: String, label: String): String =
        if (label.isBlank()) original else "$original • $label"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Favorites

    fun isFavorite(context: Context, channel: Channel): Boolean =
        prefs(context).contains(favoriteKey(channel.provider, channel.url))

    fun setFavorite(context: Context, channel: Channel, favorite: Boolean) {
        val editor = prefs(context).edit()
        if (favorite) {
            editor.putString(favoriteKey(channel.provider, channel.url), channel.name)
        } else {
            editor.remove(favoriteKey(channel.provider, channel.url))
        }
        editor.apply()
    }

    /** (provider, url) pairs for every favorited channel. */
    fun favoriteKeys(context: Context): Set<Pair<String, String>> {
        return prefs(context).all.keys.mapNotNull { key ->
            val parsed = parseKey(key) ?: return@mapNotNull null
            if (parsed.first == KIND_FAVORITE) parsed.second to parsed.third else null
        }.toSet()
    }

    // Hidden channels

    fun isHidden(context: Context, channel: Channel): Boolean =
        prefs(context).contains(hiddenKey(channel.provider, channel.url))

    fun hide(context: Context, channel: Channel) {
        prefs(context).edit()
            .putString(hiddenKey(channel.provider, channel.url), channel.name)
            .apply()
    }

    fun unhide(context: Context, provider: String, url: String) {
        prefs(context).edit().remove(hiddenKey(provider, url)).apply()
    }

    /** Hidden channels, sorted by name, for the manage-hidden screen. */
    fun hiddenChannels(context: Context): List<HiddenChannel> {
        return prefs(context).all.mapNotNull { (key, value) ->
            val parsed = parseKey(key) ?: return@mapNotNull null
            if (parsed.first == KIND_HIDDEN) {
                HiddenChannel(parsed.second, parsed.third, value as? String ?: parsed.third)
            } else null
        }.sortedBy { it.name }
    }

    // Group renames

    fun getLabel(context: Context, provider: String, group: String): String =
        prefs(context).getString(labelKey(provider, group), "") ?: ""

    /** A blank label clears the rename and restores the original name alone. */
    fun setLabel(context: Context, provider: String, group: String, label: String) {
        val editor = prefs(context).edit()
        if (label.isBlank()) editor.remove(labelKey(provider, group))
        else editor.putString(labelKey(provider, group), label)
        editor.apply()
    }
}
