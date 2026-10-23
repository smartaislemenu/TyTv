package com.tytv.app.ui

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.model.Channel

/** A row card that launches an action instead of a channel. */
data class ActionCard(val title: String, val description: String, val action: String) {
    companion object {
        const val ADD_PROVIDER = "add_provider"
        const val BUFFER_SETTINGS = "buffer_settings"
        const val CHECK_UPDATES = "check_updates"
        const val HIDDEN_CHANNELS = "hidden_channels"
        const val RENAME_GROUPS = "rename_groups"
        const val FAVORITES_HELP = "favorites_help"
    }
}

/**
 * One presenter for both channel cards and action cards. Leanback's
 * ImageCardView gives standard TV focus handling for free, so D-pad
 * arrows move between cards and the select button activates them —
 * no mouse or touch input is needed anywhere in the browse UI.
 *
 * Channel cards also report a long-press (remote select held down) via
 * [onChannelLongPress], which the browse screen uses for the channel
 * menu (play / favorite / hide / rename group). The bound item is kept
 * in the card's tag so the long-press always names the right channel.
 */
class CardPresenter : Presenter() {
    var onChannelLongPress: ((Channel) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(320, 180)
        }
        cardView.setOnLongClickListener { view ->
            val channel = view.tag as? Channel
                ?: return@setOnLongClickListener false
            val listener = onChannelLongPress
                ?: return@setOnLongClickListener false
            listener(channel)
            true
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        cardView.tag = item
        when (item) {
            is Channel -> {
                val context = cardView.context
                val display = ChannelPrefs.displayGroup(
                    item.group,
                    ChannelPrefs.getLabel(context, item.provider, item.group)
                )
                val star = if (ChannelPrefs.isFavorite(context, item)) "★ " else ""
                cardView.titleText = "$star${item.name}"
                cardView.contentText = "$display • ${item.provider}"
            }
            is ActionCard -> {
                cardView.titleText = item.title
                cardView.contentText = item.description
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // Nothing to release; cards carry text only (no loaded images yet).
    }
}
