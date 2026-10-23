package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tytv.app.R
import com.tytv.app.model.Channel
import com.tytv.app.settings.BufferSettings
import com.tytv.app.timeshift.RecordingsStore
import com.tytv.app.timeshift.TimeshiftProxy
import com.tytv.app.timeshift.TimeshiftSession
import java.io.File

/**
 * Single-channel playback with ExoPlayer (Media3). The player's load
 * control comes from the user's buffer-size setting, so the Low /
 * Balanced / High / Extra presets change real buffering behavior.
 * Playback controls are the standard Media3 controller, which responds
 * to D-pad input on Android TV, plus a TYTV action bar (top-right,
 * shown with the controller) for Record, Tracks, Sleep, and Go live.
 *
 * Dolby: encoded Dolby audio (AC-3 / E-AC-3) passes through untouched
 * whenever the device and output support it — the player never forces
 * a downmix to stereo. The info line names the active audio format so
 * it is visible when 5.1 is actually playing; a stereo stream stays
 * stereo, because surround sound cannot be invented from it.
 *
 * Timeshift (direct TS streams only): while watching, the stream is
 * buffered to a local file and played back through a loopback proxy,
 * so pause freezes the read position, seek rewinds within the buffer,
 * and Record keeps the file as a recording on exit. Rewind only
 * reaches back to when this channel session started. HLS (.m3u8) and
 * DASH streams play directly without timeshift — their segmented
 * format needs segment-level handling this version does not do — and
 * the Record and Go live buttons hide for them.
 */
class PlaybackActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var channelNameView: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var recordButton: Button
    private lateinit var goLiveButton: Button
    private var player: ExoPlayer? = null

    private var channelName = ""
    private var streamUrl = ""
    private var timeshiftEnabled = false
    private var timeshiftSession: TimeshiftSession? = null
    private var timeshiftProxy: TimeshiftProxy? = null
    private var sleepRunnable: Runnable? = null
    private val sleepHandler = Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateInfoText()
        }
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LOCAL = "extra_local"

        fun intent(context: Context, channel: Channel): Intent {
            return Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_URL, channel.url)
                putExtra(EXTRA_NAME, channel.name)
                putExtra(EXTRA_LOCAL, false)
            }
        }

        fun intentForFile(context: Context, file: File, displayName: String): Intent {
            return Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_URL, Uri.fromFile(file).toString())
                putExtra(EXTRA_NAME, displayName)
                putExtra(EXTRA_LOCAL, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)
        channelNameView = findViewById(R.id.channel_name)
        actionBar = findViewById(R.id.action_bar)
        infoText = findViewById(R.id.info_text)
        recordButton = findViewById(R.id.record_button)
        goLiveButton = findViewById(R.id.golive_button)
        channelNameView.text = intent.getStringExtra(EXTRA_NAME) ?: ""
        channelName = intent.getStringExtra(EXTRA_NAME) ?: ""
        streamUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                actionBar.visibility = visibility
            }
        )
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.tracks_button).setOnClickListener { showTrackChooser() }
        findViewById<Button>(R.id.sleep_button).setOnClickListener { showSleepDialog() }
        goLiveButton.setOnClickListener { goLive() }
    }

    override fun onStart() {
        super.onStart()
        if (streamUrl.isEmpty()) return
        val local = intent.getBooleanExtra(EXTRA_LOCAL, false)
        val bufferSize = BufferSettings.get(this)
        var playUrl = streamUrl
        timeshiftEnabled = false
        if (!local && isDirectTsStream(streamUrl)) {
            try {
                val bufferFile = File(
                    File(cacheDir, "timeshift"),
                    "buffer_${System.currentTimeMillis()}.ts"
                )
                val session = TimeshiftSession(streamUrl, bufferFile)
                session.start()
                val proxy = TimeshiftProxy(session)
                proxy.start()
                timeshiftSession = session
                timeshiftProxy = proxy
                playUrl = proxy.streamUrl()
                timeshiftEnabled = true
            } catch (e: Exception) {
                timeshiftEnabled = false
                Toast.makeText(
                    this,
                    R.string.timeshift_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        recordButton.visibility = if (timeshiftEnabled) View.VISIBLE else View.GONE
        goLiveButton.visibility = if (timeshiftEnabled) View.VISIBLE else View.GONE
        player = ExoPlayer.Builder(this)
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
                exoPlayer.addListener(playerListener)
                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(playUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        updateInfoText()
        channelNameView.postDelayed({ channelNameView.visibility = View.GONE }, 5000)
    }

    override fun onStop() {
        super.onStop()
        sleepHandler.removeCallbacksAndMessages(null)
        sleepRunnable = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        val session = timeshiftSession
        val proxy = timeshiftProxy
        timeshiftSession = null
        timeshiftProxy = null
        if (proxy != null) proxy.stop()
        if (session != null) {
            // Finish on a background thread: the download thread needs a
            // moment to close the buffer file after disconnect, and the
            // file must be complete before it is saved or deleted.
            kotlin.concurrent.thread(name = "tytv-timeshift-finish", isDaemon = true) {
                var waited = 0
                while (!session.finished && waited < 3000) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                    waited += 50
                }
                if (session.recording) {
                    val saved = RecordingsStore.save(this, session.bufferFile, channelName)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (saved) R.string.recording_saved else R.string.recording_save_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    try {
                        session.bufferFile.delete()
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    /** Direct TS streams are timeshiftable; HLS/DASH/local files are not. */
    private fun isDirectTsStream(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
            !lower.contains(".m3u8") && !lower.contains(".mpd")
    }

    private fun toggleRecording() {
        val session = timeshiftSession ?: return
        session.recording = !session.recording
        if (session.recording) {
            recordButton.text = getString(R.string.recording_active)
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_LONG).show()
        } else {
            recordButton.text = getString(R.string.record)
            Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_LONG).show()
        }
    }

    /** Jumps back to the live edge: a fresh proxy connection near the write position. */
    private fun goLive() {
        val proxy = timeshiftProxy ?: return
        val exoPlayer = player ?: return
        exoPlayer.setMediaItem(MediaItem.fromUri(proxy.liveUrl()))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun updateInfoText() {
        val exoPlayer = player
        var audioDesc: String? = null
        if (exoPlayer != null) {
            for (group in exoPlayer.currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.length > 0) {
                    val format = group.getTrackFormat(0)
                    audioDesc = describeAudio(format.sampleMimeType, format.channelCount)
                    break
                }
            }
        }
        val timeshiftNote = if (timeshiftEnabled) {
            getString(R.string.info_timeshift_on)
        } else {
            null
        }
        infoText.text = listOfNotNull(channelName, audioDesc, timeshiftNote)
            .joinToString(" • ")
    }

    private fun describeAudio(mime: String?, channelCount: Int): String {
        val channels = when (channelCount) {
            6 -> " 5.1"
            2 -> " Stereo"
            1 -> " Mono"
            else -> ""
        }
        return when (mime) {
            "audio/ac3" -> "Dolby Digital$channels"
            "audio/eac3" -> "Dolby Digital Plus$channels"
            "audio/aac" -> "AAC$channels"
            "audio/mpeg" -> "MP3$channels"
            null -> "Audio$channels"
            else -> "$mime$channels"
        }
    }

    private fun showTrackChooser() {
        val exoPlayer = player
        if (exoPlayer == null) return
        val hasAudio = exoPlayer.currentTracks.groups.any {
            it.type == C.TRACK_TYPE_AUDIO && it.length > 0
        }
        val hasText = exoPlayer.currentTracks.groups.any {
            it.type == C.TRACK_TYPE_TEXT && it.length > 0
        }
        if (!hasAudio && !hasText) {
            Toast.makeText(this, R.string.tracks_none, Toast.LENGTH_SHORT).show()
            return
        }
        val options = mutableListOf<String>()
        if (hasAudio) options += getString(R.string.tracks_audio_title)
        if (hasText) options += getString(R.string.tracks_subtitles_title)
        AlertDialog.Builder(this)
            .setTitle(R.string.tracks)
            .setItems(options.toTypedArray()) { _, which ->
                val picked = options[which]
                if (picked == getString(R.string.tracks_audio_title)) {
                    showAudioTrackDialog()
                } else {
                    showSubtitleTrackDialog()
                }
            }
            .show()
    }

    private fun showAudioTrackDialog() {
        val exoPlayer = player ?: return
        data class Option(val label: String, val group: TrackGroup?, val index: Int)
        val options = mutableListOf(Option(getString(R.string.tracks_auto), null, -1))
        for (group in exoPlayer.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label
                    ?: format.language
                    ?: "Audio ${options.size}"
                options += Option(label, group.mediaTrackGroup, i)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tracks_audio_title)
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val picked = options[which]
                val params = exoPlayer.trackSelectionParameters.buildUpon()
                if (picked.group == null) {
                    params.clearOverrideForType(C.TRACK_TYPE_AUDIO)
                } else {
                    params.setOverrideForType(
                        TrackSelectionOverride(picked.group, listOf(picked.index))
                    )
                }
                exoPlayer.trackSelectionParameters = params.build()
                updateInfoText()
            }
            .show()
    }

    private fun showSubtitleTrackDialog() {
        val exoPlayer = player ?: return
        data class Option(val label: String, val group: TrackGroup?, val index: Int)
        val options = mutableListOf(Option(getString(R.string.tracks_off), null, -1))
        for (group in exoPlayer.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label
                    ?: format.language
                    ?: "Subtitle ${options.size}"
                options += Option(label, group.mediaTrackGroup, i)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tracks_subtitles_title)
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val picked = options[which]
                val params = exoPlayer.trackSelectionParameters.buildUpon()
                if (picked.group == null) {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    params.setOverrideForType(
                        TrackSelectionOverride(picked.group, listOf(picked.index))
                    )
                }
                exoPlayer.trackSelectionParameters = params.build()
            }
            .show()
    }

    private fun showSleepDialog() {
        val choices = arrayOf(
            getString(R.string.sleep_off),
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_60),
            getString(R.string.sleep_90)
        )
        val minutes = arrayOf(0, 15, 30, 60, 90)
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(choices) { _, which ->
                sleepHandler.removeCallbacksAndMessages(null)
                sleepRunnable = null
                val mins = minutes[which]
                if (mins > 0) {
                    val runnable = Runnable {
                        player?.pause()
                        Toast.makeText(
                            this,
                            R.string.sleep_timer_fired,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    sleepRunnable = runnable
                    sleepHandler.postDelayed(runnable, mins * 60_000L)
                    Toast.makeText(
                        this,
                        getString(R.string.sleep_timer_set, mins),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }
}
