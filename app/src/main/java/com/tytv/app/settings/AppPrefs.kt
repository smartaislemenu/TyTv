package com.tytv.app.settings

import android.content.Context
import android.util.Base64

/**
 * App-wide preferences that live in the main prefs file: font choice,
 * player choice, scores toggle, and parental controls. Buffer size
 * lives in [BufferSettings] and the guide address in EpgStore, both in
 * the same file.
 *
 * The parental PIN ([KEY_PIN]) is deliberately excluded from backups
 * (see BackupStore): a backup file must never unlock parental
 * controls on its own.
 */
object AppPrefs {
    private const val PREFS = "tytv_prefs"
    const val KEY_PIN = "parental_pin"
    private const val KEY_FONT = "app_font"
    private const val KEY_PLAYER = "player_mode"
    private const val KEY_SCORES = "scores_enabled"
    private const val KEY_LOCKED_GROUPS = "locked_groups"

    enum class AppFont(val label: String, val family: String) {
        DEFAULT("Default", "sans-serif"),
        CONDENSED("Condensed", "sans-serif-condensed"),
        SERIF("Serif", "serif"),
        MONOSPACE("Monospace", "monospace")
    }

    enum class PlayerMode(val label: String) {
        BUILTIN("Built-in player"),
        EXTERNAL("Outside player (VLC etc.)")
    }

    /** Unlocked once per app process after the PIN is entered. */
    @Volatile var sessionUnlocked = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun font(context: Context): AppFont {
        val name = prefs(context).getString(KEY_FONT, null)
        return AppFont.values().firstOrNull { it.name == name } ?: AppFont.DEFAULT
    }

    fun setFont(context: Context, font: AppFont) {
        prefs(context).edit().putString(KEY_FONT, font.name).apply()
    }

    fun playerMode(context: Context): PlayerMode {
        val name = prefs(context).getString(KEY_PLAYER, null)
        return PlayerMode.values().firstOrNull { it.name == name } ?: PlayerMode.BUILTIN
    }

    fun setPlayerMode(context: Context, mode: PlayerMode) {
        prefs(context).edit().putString(KEY_PLAYER, mode.name).apply()
    }

    fun scoresEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCORES, true)

    fun setScoresEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCORES, enabled).apply()
    }

    fun pin(context: Context): String? =
        prefs(context).getString(KEY_PIN, null)?.ifEmpty { null }

    fun setPin(context: Context, pin: String?) {
        val editor = prefs(context).edit()
        if (pin.isNullOrEmpty()) editor.remove(KEY_PIN) else editor.putString(KEY_PIN, pin)
        editor.apply()
        sessionUnlocked = false
    }

    private fun encodePart(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodePart(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Locked (provider, group) pairs, stored Base64 so group names
     *  containing separators can't collide. */
    fun lockedGroups(context: Context): Set<Pair<String, String>> {
        val raw = prefs(context).getStringSet(KEY_LOCKED_GROUPS, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split(".")
            if (parts.size != 2) return@mapNotNull null
            val provider = decodePart(parts[0]) ?: return@mapNotNull null
            val group = decodePart(parts[1]) ?: return@mapNotNull null
            provider to group
        }.toSet()
    }

    fun setLockedGroups(context: Context, groups: Set<Pair<String, String>>) {
        val raw = groups.map { "${encodePart(it.first)}.${encodePart(it.second)}" }.toSet()
        prefs(context).edit().putStringSet(KEY_LOCKED_GROUPS, raw).apply()
    }

    fun isGroupLocked(context: Context, provider: String, group: String): Boolean =
        lockedGroups(context).contains(provider to group)
}
