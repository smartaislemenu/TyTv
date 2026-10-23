package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.model.Channel
import com.tytv.app.settings.AppPrefs

/**
 * The long-press channel menu, shared by the home list and search
 * results: Play, Add/Remove Favorite, Hide channel, Rename group.
 * [onChanged] runs after a favorite/hide change so the caller can
 * refresh its list. Series items get Play replaced with Episodes.
 */
object ChannelMenu {

    fun show(context: Context, channel: Channel, onChanged: () -> Unit) {
        val isFavorite = ChannelPrefs.isFavorite(context, channel)
        val playable = !channel.url.startsWith("xtream:series:")
        val items = mutableListOf<String>()
        items += context.getString(
            if (channel.kind == com.tytv.app.model.ChannelKind.SERIES) {
                R.string.channel_menu_episodes
            } else {
                R.string.channel_menu_play
            }
        )
        items += context.getString(
            if (isFavorite) {
                R.string.channel_menu_remove_favorite
            } else {
                R.string.channel_menu_add_favorite
            }
        )
        items += context.getString(R.string.channel_menu_hide)
        items += context.getString(R.string.channel_menu_rename_group)
        if (playable) items += context.getString(R.string.channel_menu_play_external)
        if (playable) items += context.getString(R.string.channel_menu_multiview)
        AlertDialog.Builder(context)
            .setTitle(channel.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> play(context, channel)
                    1 -> {
                        ChannelPrefs.setFavorite(context, channel, !isFavorite)
                        Toast.makeText(
                            context,
                            if (isFavorite) {
                                R.string.channel_unfavorited
                            } else {
                                R.string.channel_favorited
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        onChanged()
                    }
                    2 -> {
                        ChannelPrefs.hide(context, channel)
                        Toast.makeText(
                            context,
                            R.string.channel_hidden,
                            Toast.LENGTH_LONG
                        ).show()
                        onChanged()
                    }
                    3 -> context.startActivity(
                        RenameGroupActivity.intent(context, channel.provider, channel.group)
                    )
                    4 -> playExternally(context, channel)
                    5 -> openMultiView(context, channel)
                }
            }
            .show()
    }

    /**
     * Plain play action for a tapped row. Only Xtream series markers
     * open their episode list; M3U series-group entries carry real
     * stream URLs and play directly like everything else.
     *
     * Parental lock runs first: a locked group asks for the PIN once
     * per app session, then plays. The player setting runs second:
     * "Outside player" hands the direct stream URL to an installed
     * player app (VLC etc.) instead of the built-in player. Rewind
     * and recording only work in the built-in player, so they are
     * not available on the external path.
     */
    fun play(context: Context, channel: Channel) {
        if (AppPrefs.isGroupLocked(context, channel.provider, channel.group) &&
            !AppPrefs.sessionUnlocked
        ) {
            PinPadDialog.show(context, context.getString(R.string.pin_enter)) { entered ->
                if (entered == AppPrefs.pin(context)) {
                    AppPrefs.sessionUnlocked = true
                    playNow(context, channel)
                } else {
                    Toast.makeText(context, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        playNow(context, channel)
    }

    private fun playNow(context: Context, channel: Channel) {
        if (AppPrefs.playerMode(context) == AppPrefs.PlayerMode.EXTERNAL) {
            playExternally(context, channel)
            return
        }
        if (channel.kind == com.tytv.app.model.ChannelKind.SERIES &&
            channel.url.startsWith("xtream:series:")
        ) {
            context.startActivity(
                SeriesEpisodesActivity.intent(
                    context,
                    channel.provider,
                    channel.seriesId ?: "",
                    channel.name
                )
            )
        } else {
            context.startActivity(PlaybackActivity.intent(context, channel))
        }
    }

    /** Multi-view entry: PIN first for locked groups, then 2 or 4 tiles. */
    private fun openMultiView(context: Context, channel: Channel) {
        if (AppPrefs.isGroupLocked(context, channel.provider, channel.group) &&
            !AppPrefs.sessionUnlocked
        ) {
            PinPadDialog.show(context, context.getString(R.string.pin_enter)) { entered ->
                if (entered == AppPrefs.pin(context)) {
                    AppPrefs.sessionUnlocked = true
                    askTileCount(context, channel)
                } else {
                    Toast.makeText(context, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        askTileCount(context, channel)
    }

    private fun askTileCount(context: Context, channel: Channel) {
        AlertDialog.Builder(context)
            .setTitle(R.string.channel_menu_multiview)
            .setItems(
                arrayOf(
                    context.getString(R.string.multiview_2),
                    context.getString(R.string.multiview_4)
                )
            ) { _, which ->
                context.startActivity(
                    MultiViewActivity.intent(context, channel, if (which == 0) 2 else 4)
                )
            }
            .show()
    }

    /** Hands the direct stream URL to an installed player app. */
    fun playExternally(context: Context, channel: Channel) {
        if (channel.url.startsWith("xtream:series:")) return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(channel.url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(view, channel.name))
        } catch (e: Exception) {
            Toast.makeText(
                context,
                R.string.player_no_external_app,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
