package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.model.Channel

/**
 * The long-press channel menu, shared by the home list and search
 * results: Play, Add/Remove Favorite, Hide channel, Rename group.
 * [onChanged] runs after a favorite/hide change so the caller can
 * refresh its list. Series items get Play replaced with Episodes.
 */
object ChannelMenu {

    fun show(context: Context, channel: Channel, onChanged: () -> Unit) {
        val isFavorite = ChannelPrefs.isFavorite(context, channel)
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
        AlertDialog.Builder(context)
            .setTitle(channel.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (channel.kind == com.tytv.app.model.ChannelKind.SERIES) {
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
                }
            }
            .show()
    }

    /** Plain play action for a tapped row (series open their episode list). */
    fun play(context: Context, channel: Channel) {
        if (channel.kind == com.tytv.app.model.ChannelKind.SERIES) {
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
}
