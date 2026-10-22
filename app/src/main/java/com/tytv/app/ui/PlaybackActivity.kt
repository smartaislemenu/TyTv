package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tytv.app.R
import com.tytv.app.model.Channel
import com.tytv.app.settings.BufferSettings

/**
 * Single-channel playback with ExoPlayer (Media3). The player's load
 * control comes from the user's buffer-size setting, so the Low /
 * Balanced / High / Extra presets change real buffering behavior.
 * Playback controls are the standard Media3 controller, which responds
 * to D-pad input on Android TV. Views are looked up directly (no view
 * binding) so the screen builds with or without the Gradle plugin.
 */
class PlaybackActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var channelNameView: TextView
    private var player: ExoPlayer? = null

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_NAME = "extra_name"

        fun intent(context: Context, channel: Channel): Intent {
            return Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_URL, channel.url)
                putExtra(EXTRA_NAME, channel.name)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)
        channelNameView = findViewById(R.id.channel_name)
        channelNameView.text = intent.getStringExtra(EXTRA_NAME) ?: ""
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val bufferSize = BufferSettings.get(this)
        player = ExoPlayer.Builder(this)
            .setLoadControl(BufferSettings.loadControl(bufferSize))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        channelNameView.postDelayed({ channelNameView.visibility = View.GONE }, 5000)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
