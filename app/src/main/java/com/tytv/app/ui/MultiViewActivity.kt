package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tytv.app.R
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.settings.AppPrefs
import com.tytv.app.settings.BufferSettings

/**
 * Multi-view: 2 or 4 live channels at once, side by side. Tiles play
 * their direct streams (no timeshift buffering, so four tiles do not
 * quadruple the download work); audio follows the focused tile, and
 * pressing OK on a tile expands it to the full player. Empty tiles
 * open the channel search as a picker. The first channel arrives
 * from the long-press menu; the rest are picked per tile.
 */
class MultiViewActivity : FragmentActivity() {

    companion object {
        private const val EXTRA_FIRST_CHANNEL_NAME = "mv_name"
        private const val EXTRA_FIRST_CHANNEL_URL = "mv_url"
        private const val EXTRA_FIRST_CHANNEL_GROUP = "mv_group"
        private const val EXTRA_FIRST_CHANNEL_PROVIDER = "mv_provider"
        private const val EXTRA_FIRST_CHANNEL_TVG_ID = "mv_tvg_id"
        private const val EXTRA_FIRST_CHANNEL_NUMBER = "mv_number"
        private const val EXTRA_TILE_COUNT = "mv_tiles"

        fun intent(context: Context, first: Channel, tileCount: Int): Intent {
            return Intent(context, MultiViewActivity::class.java).apply {
                putExtra(EXTRA_FIRST_CHANNEL_NAME, first.name)
                putExtra(EXTRA_FIRST_CHANNEL_URL, first.url)
                putExtra(EXTRA_FIRST_CHANNEL_GROUP, first.group)
                putExtra(EXTRA_FIRST_CHANNEL_PROVIDER, first.provider)
                putExtra(EXTRA_FIRST_CHANNEL_TVG_ID, first.tvgId)
                first.number?.let { putExtra(EXTRA_FIRST_CHANNEL_NUMBER, it) }
                putExtra(EXTRA_TILE_COUNT, tileCount)
            }
        }
    }

    private class Tile(
        val container: View,
        val playerView: PlayerView,
        val label: TextView,
        var channel: Channel? = null,
        var player: ExoPlayer? = null
    )

    private val tiles = mutableListOf<Tile>()
    private var tileCount = 2
    private var started = false
    private var focusedTile = 0
    private var pendingTile = -1

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || pendingTile < 0) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val channel = SearchActivity.pickedChannel(data) ?: return@registerForActivityResult
        val target = pendingTile
        pendingTile = -1
        if (AppPrefs.isGroupLocked(this, channel.provider, channel.group) &&
            !AppPrefs.sessionUnlocked
        ) {
            PinPadDialog.show(this, getString(R.string.pin_enter)) { entered ->
                if (entered == AppPrefs.pin(this)) {
                    AppPrefs.sessionUnlocked = true
                    fillTile(target, channel)
                } else {
                    Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            return@registerForActivityResult
        }
        fillTile(target, channel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiview)
        tileCount = intent.getIntExtra(EXTRA_TILE_COUNT, 2).coerceIn(2, 4)
        val first = Channel(
            name = intent.getStringExtra(EXTRA_FIRST_CHANNEL_NAME) ?: "",
            url = intent.getStringExtra(EXTRA_FIRST_CHANNEL_URL) ?: "",
            group = intent.getStringExtra(EXTRA_FIRST_CHANNEL_GROUP) ?: "",
            logo = null,
            provider = intent.getStringExtra(EXTRA_FIRST_CHANNEL_PROVIDER) ?: "",
            tvgId = intent.getStringExtra(EXTRA_FIRST_CHANNEL_TVG_ID),
            number = if (intent.hasExtra(EXTRA_FIRST_CHANNEL_NUMBER)) {
                intent.getIntExtra(EXTRA_FIRST_CHANNEL_NUMBER, -1).takeIf { it > 0 }
            } else null,
            kind = ChannelKind.LIVE
        )
        val root = findViewById<LinearLayout>(R.id.multiview_root)
        val rows = if (tileCount == 2) 1 else 2
        var tileIndex = 0
        for (row in 0 until rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
            for (col in 0 until 2) {
                if (tileIndex >= tileCount) break
                val index = tileIndex
                val tileView = LayoutInflater.from(this)
                    .inflate(R.layout.item_multiview_tile, rowLayout, false)
                val tile = Tile(
                    container = tileView,
                    playerView = tileView.findViewById(R.id.tile_player),
                    label = tileView.findViewById(R.id.tile_label),
                    channel = if (index == 0) first else null
                )
                tile.playerView.useController = false
                tile.container.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        focusedTile = index
                        updateVolumes()
                    }
                }
                tile.container.setOnClickListener {
                    val channel = tile.channel
                    if (channel == null) {
                        pendingTile = index
                        val pick = Intent(this, SearchActivity::class.java).apply {
                            putExtra(SearchActivity.EXTRA_PICK, true)
                        }
                        pickerLauncher.launch(pick)
                    } else {
                        startActivity(PlaybackActivity.intent(this, channel))
                    }
                }
                tiles += tile
                rowLayout.addView(tileView)
                tileIndex++
            }
            root.addView(rowLayout)
        }
        refreshLabels()
        tiles.firstOrNull()?.container?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        started = true
        for (tile in tiles) startTile(tile)
        updateVolumes()
    }

    override fun onStop() {
        super.onStop()
        started = false
        for (tile in tiles) stopTile(tile)
    }

    private fun fillTile(index: Int, channel: Channel) {
        if (index < 0 || index >= tiles.size) return
        val tile = tiles[index]
        stopTile(tile)
        tile.channel = channel
        refreshLabels()
        if (started) startTile(tile)
        tile.container.requestFocus()
    }

    private fun startTile(tile: Tile) {
        val channel = tile.channel ?: return
        if (tile.player != null) return
        val bufferSize = BufferSettings.get(this)
        tile.player = ExoPlayer.Builder(this)
            .setLoadControl(BufferSettings.loadControl(bufferSize))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
            .also { exoPlayer ->
                tile.playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(channel.url))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        updateVolumes()
    }

    private fun stopTile(tile: Tile) {
        tile.player?.release()
        tile.player = null
        tile.playerView.player = null
    }

    /** Only the focused tile is audible; the rest play muted. */
    private fun updateVolumes() {
        for ((index, tile) in tiles.withIndex()) {
            tile.player?.volume = if (index == focusedTile) 1f else 0f
        }
    }

    private fun refreshLabels() {
        for (tile in tiles) {
            tile.label.text = tile.channel?.name
                ?: getString(R.string.multiview_add_channel)
        }
    }
}
