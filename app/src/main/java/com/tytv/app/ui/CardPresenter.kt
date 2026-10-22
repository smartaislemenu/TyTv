package com.tytv.app.ui

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.tytv.app.model.Channel

/** A row card that launches an action instead of a channel. */
data class ActionCard(val title: String, val description: String, val action: String) {
    companion object {
        const val ADD_PROVIDER = "add_provider"
        const val BUFFER_SETTINGS = "buffer_settings"
        const val CHECK_UPDATES = "check_updates"
    }
}

/**
 * One presenter for both channel cards and action cards. Leanback's
 * ImageCardView gives standard TV focus handling for free, so D-pad
 * arrows move between cards and the select button activates them —
 * no mouse or touch input is needed anywhere in the browse UI.
 */
class CardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(320, 180)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        when (item) {
            is Channel -> {
                cardView.titleText = item.name
                cardView.contentText = "${item.group} • ${item.provider}"
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
