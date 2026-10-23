package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
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
import com.tytv.app.epg.EpgMatcher
import com.tytv.app.epg.EpgProgramme
import com.tytv.app.epg.EpgStore
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.data.ContinueStore
import com.tytv.app.data.RecentStore
import com.tytv.app.model.CatchupUrl
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.settings.AppPrefs
import com.tytv.app.settings.BufferSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var restartButton: Button
    private lateinit var lastButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private var player: ExoPlayer? = null

    private var channelName = ""
    private var channelNumber: Int? = null
    private var channelGroup = ""
    private var channelKind = ChannelKind.LIVE
    private var catchupSource: String? = null
    private var catchupHours: Int? = null
    private var autoRecord = false
    private var isLocal = false
    private var streamUrl = ""
    /** The channel's live URL, distinct from [streamUrl] while a
     *  catch-up replay is playing: surfing, recents and the Last
     *  button all key on the live identity, not the replay URL. */
    private var liveUrl = ""
    private var channelTvgId: String? = null
    private var channelProvider = ""
    private var lastBackMs = 0L
    /** Hides the channel banner; stored so a restart can cancel it. */
    private val hideBannerRunnable = Runnable { channelNameView.visibility = View.GONE }

    /** Surf order + position within it; empty until the live list loads. */
    private var surfList: List<Channel> = emptyList()
    private var surfIndex = -1
    private var timeshiftEnabled = false
    private var timeshiftSession: TimeshiftSession? = null
    private var timeshiftProxy: TimeshiftProxy? = null
    private var sleepRunnable: Runnable? = null
    private val sleepHandler = Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateInfoText()
        }

        override fun onPlaybackStateChanged(state: Int) {
            loadingSpinner.visibility =
                if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            // A finished on-demand stream has nothing left to resume.
            if (state == Player.STATE_ENDED && isVod()) {
                ContinueStore.clear(this@PlaybackActivity, channelProvider, streamUrl)
            }
        }
    }

    /** On-demand streams (movies, episodes) are the only resumable kind. */
    private fun isVod(): Boolean = !isLocal && channelKind == ChannelKind.MOVIE

    private var continueRunnable: Runnable? = null

    /** Persists the current on-demand position for resume. */
    private fun saveContinue() {
        val exoPlayer = player ?: return
        if (!isVod()) return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        ContinueStore.save(
            this, channelProvider, streamUrl, channelName,
            exoPlayer.currentPosition, duration
        )
    }

    private fun startContinueSaver() {
        if (!isVod()) return
        val runnable = object : Runnable {
            override fun run() {
                saveContinue()
                sleepHandler.postDelayed(this, 30_000L)
            }
        }
        continueRunnable = runnable
        sleepHandler.postDelayed(runnable, 30_000L)
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LOCAL = "extra_local"
        private const val EXTRA_TVG_ID = "extra_tvg_id"
        private const val EXTRA_PROVIDER = "extra_provider"
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_GROUP = "extra_group"
        private const val EXTRA_KIND = "extra_kind"
        private const val EXTRA_CATCHUP_SOURCE = "extra_catchup_source"
        private const val EXTRA_CATCHUP_HOURS = "extra_catchup_hours"

        /** Set by the reminder alert to arm recording on start. */
        const val EXTRA_AUTO_RECORD = "extra_auto_record"

        fun intent(context: Context, channel: Channel): Intent {
            return Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_URL, channel.url)
                putExtra(EXTRA_NAME, channel.name)
                putExtra(EXTRA_LOCAL, false)
                putExtra(EXTRA_TVG_ID, channel.tvgId)
                putExtra(EXTRA_PROVIDER, channel.provider)
                putExtra(EXTRA_GROUP, channel.group)
                putExtra(EXTRA_KIND, channel.kind.name)
                channel.number?.let { putExtra(EXTRA_NUMBER, it) }
                channel.catchupSource?.let { putExtra(EXTRA_CATCHUP_SOURCE, it) }
                channel.catchupHours?.let { putExtra(EXTRA_CATCHUP_HOURS, it) }
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
        restartButton = findViewById(R.id.restart_button)
        lastButton = findViewById(R.id.last_button)
        loadingSpinner = findViewById(R.id.loading_spinner)
        channelName = intent.getStringExtra(EXTRA_NAME) ?: ""
        channelNumber = if (intent.hasExtra(EXTRA_NUMBER)) {
            intent.getIntExtra(EXTRA_NUMBER, -1).takeIf { it > 0 }
        } else null
        channelGroup = intent.getStringExtra(EXTRA_GROUP) ?: ""
        channelKind = try {
            ChannelKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "LIVE")
        } catch (e: Exception) {
            ChannelKind.LIVE
        }
        catchupSource = intent.getStringExtra(EXTRA_CATCHUP_SOURCE)
        catchupHours = if (intent.hasExtra(EXTRA_CATCHUP_HOURS)) {
            intent.getIntExtra(EXTRA_CATCHUP_HOURS, 0).takeIf { it > 0 }
        } else null
        isLocal = intent.getBooleanExtra(EXTRA_LOCAL, false)
        autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)
        channelNameView.text = displayName()
        streamUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        liveUrl = streamUrl
        channelTvgId = intent.getStringExtra(EXTRA_TVG_ID)
        channelProvider = intent.getStringExtra(EXTRA_PROVIDER) ?: ""
        restartButton.setOnClickListener { restartPlayback() }
        lastButton.setOnClickListener { jumpToLast() }
        // Double-press Back to leave the player: a single Back only
        // shows the controller and a hint, so an accidental press
        // never dumps the viewer out of the channel.
        // Explicit callback object: the lambda addCallback overload
        // lives in activity-ktx, which this app does not depend on.
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackMs < 2000) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    lastBackMs = now
                    playerView.showController()
                    Toast.makeText(
                        this@PlaybackActivity,
                        R.string.player_back_again_to_exit,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
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
        startPlayback()
    }

    /**
     * (Re)starts playback for the current channel fields. Called on
     * activity start and after every surf, so switching channels
     * reuses the exact same setup path as opening one.
     */
    private fun startPlayback() {
        if (streamUrl.isEmpty()) return
        val local = isLocal
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
                // Resume an unfinished on-demand stream where it stopped.
                if (isVod()) {
                    val saved = ContinueStore.get(this, channelProvider, streamUrl)
                    if (saved != null && saved.positionMs > 30_000L &&
                        (saved.durationMs <= 0 || saved.positionMs < saved.durationMs - 60_000L)
                    ) {
                        exoPlayer.seekTo(saved.positionMs)
                        Toast.makeText(
                            this,
                            getString(R.string.resume_from, formatPosition(saved.positionMs)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    startContinueSaver()
                }
                exoPlayer.playWhenReady = true
            }
        // Scheduled recording: arm the timeshift recording on the
        // first start only (not on every surf or restart).
        if (autoRecord) {
            autoRecord = false
            if (timeshiftEnabled) {
                toggleRecording()
            } else {
                Toast.makeText(
                    this,
                    R.string.recording_not_available,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        updateInfoText()
        // Re-show the banner on every (re)start, clearing any hide
        // queued by the previous channel so it is not cut short.
        channelNameView.visibility = View.VISIBLE
        // View has no removeCallbacksAndMessages (that is a Handler
        // method) — cancel the stored runnable instead.
        channelNameView.removeCallbacks(hideBannerRunnable)
        channelNameView.postDelayed(hideBannerRunnable, 5000)
        if (!local && channelKind == ChannelKind.LIVE) {
            RecentStore.record(
                this,
                RecentStore.Recent(channelProvider, liveUrl, channelName, channelGroup)
            )
            updateLastButton()
            loadSurfList()
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback(cancelSleep = true)
    }

    /**
     * Tears the player and timeshift session down. Surfing passes
     * cancelSleep = false so the sleep timer survives a channel
     * change; leaving the activity cancels it.
     */
    private fun stopPlayback(cancelSleep: Boolean) {
        if (cancelSleep) {
            sleepHandler.removeCallbacksAndMessages(null)
            sleepRunnable = null
        }
        // Final position first (the player is released right after),
        // then stop the periodic saver.
        if (isVod()) saveContinue()
        continueRunnable?.let { sleepHandler.removeCallbacks(it) }
        continueRunnable = null
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

    /**
     * Channel surfing: CH up/down keys always surf; DPAD up/down surf
     * only while the controller is hidden (when it is visible, DPAD
     * navigates the controller buttons instead). Dialog windows take
     * their own keys, so open dialogs are never disturbed. Keys pass
     * through untouched when there is nothing to surf.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            ::playerView.isInitialized &&
            surfList.isNotEmpty() && surfIndex >= 0
        ) {
            val up = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> true
                KeyEvent.KEYCODE_CHANNEL_DOWN -> false
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (playerView.isControllerFullyVisible) return super.dispatchKeyEvent(event)
                    else true
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (playerView.isControllerFullyVisible) return super.dispatchKeyEvent(event)
                    else false
                else -> null
            }
            if (up != null) {
                surf(if (up) 1 else -1)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Loads the surf list from the on-device cache (fast, no fetch). */
    private fun loadSurfList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = ChannelRepository.loadAll(this@PlaybackActivity, false)
            val live = outcome.channels.filter {
                it.kind == ChannelKind.LIVE &&
                    !ChannelPrefs.isHidden(this@PlaybackActivity, it)
            }
            val ordered = SurfOrder.liveOrder(live)
            val index = ordered.indexOfFirst {
                it.provider == channelProvider && it.url == liveUrl
            }
            withContext(Dispatchers.Main) {
                surfList = ordered
                surfIndex = index
            }
        }
    }

    /** Moves to the next/previous channel in surf order, wrapping at
     *  the ends. Locked groups are skipped (unless already unlocked
     *  this session); if every channel is locked, playback stays put. */
    private fun surf(direction: Int) {
        if (surfList.isEmpty() || surfIndex < 0) return
        var idx = surfIndex
        repeat(surfList.size) {
            idx = (idx + direction + surfList.size) % surfList.size
            val target = surfList[idx]
            if (!AppPrefs.isGroupLocked(this, target.provider, target.group) ||
                AppPrefs.sessionUnlocked
            ) {
                switchTo(target)
                return
            }
        }
    }

    /**
     * Switches playback to [channel] in place (no new activity).
     * [liveUrlForChannel] is the channel's live URL; it defaults to
     * the channel's own URL, but a catch-up replay passes the
     * original live URL so surfing, recents and Last keep working
     * while the replay plays.
     */
    private fun switchTo(channel: Channel, liveUrlForChannel: String = channel.url) {
        // Point the surf position at the target right away, so a
        // second surf pressed before the list reload finishes still
        // moves from the new channel instead of the old one.
        val idx = surfList.indexOfFirst {
            it.provider == channel.provider && it.url == liveUrlForChannel
        }
        if (idx >= 0) surfIndex = idx
        stopPlayback(cancelSleep = false)
        channelName = channel.name
        channelNumber = channel.number
        channelGroup = channel.group
        channelKind = channel.kind
        catchupSource = channel.catchupSource
        catchupHours = channel.catchupHours
        streamUrl = channel.url
        liveUrl = liveUrlForChannel
        channelTvgId = channel.tvgId
        channelProvider = channel.provider
        channelNameView.text = displayName()
        startPlayback()
    }

    /**
     * The channel before the current one: recording on every play
     * start puts the current channel at the head of the list, so the
     * previous channel is the first entry that is not the current one.
     */
    private fun previousChannel(): RecentStore.Recent? =
        RecentStore.list(this).firstOrNull {
            it.url != liveUrl || it.provider != channelProvider
        }

    /** Jumps back to the last-watched channel, PIN first if locked. */
    private fun jumpToLast() {
        val last = previousChannel() ?: return
        val target = surfList.firstOrNull {
            it.provider == last.provider && it.url == last.url
        } ?: Channel(
            name = last.name,
            url = last.url,
            group = last.group,
            logo = null,
            provider = last.provider
        )
        if (AppPrefs.isGroupLocked(this, target.provider, target.group) &&
            !AppPrefs.sessionUnlocked
        ) {
            PinPadDialog.show(this, getString(R.string.pin_enter)) { entered ->
                if (entered == AppPrefs.pin(this)) {
                    AppPrefs.sessionUnlocked = true
                    switchTo(target)
                } else {
                    Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        switchTo(target)
    }

    /** The Last button only shows when there is somewhere to go back to. */
    private fun updateLastButton() {
        lastButton.visibility =
            if (previousChannel() != null) View.VISIBLE else View.GONE
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
        // Now/next from whatever guide is already loaded in memory.
        // Never blocks playback on a guide fetch. With the background
        // guide preload, this is populated by the time playback starts.
        val lines = mutableListOf(displayName())
        audioDesc?.let { lines += it }
        timeshiftNote?.let { lines += it }
        if (streamUrl != liveUrl) lines += getString(R.string.info_catchup)
        // The Restart button shows only when the current programme
        // has a catch-up URL: it replays this programme from its
        // start instead of joining it live.
        var restartUrl: String? = null
        try {
            val probe = Channel(
                name = channelName,
                url = liveUrl,
                group = channelGroup,
                logo = null,
                provider = channelProvider,
                tvgId = channelTvgId,
                number = channelNumber,
                kind = channelKind,
                catchupSource = catchupSource,
                catchupHours = catchupHours
            )
            val programmes = EpgStore.findFor(probe)
            if (!programmes.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                val current = EpgMatcher.currentAt(programmes, now)
                val next = EpgMatcher.nextAfter(programmes, now)
                if (current != null) {
                    restartUrl = CatchupUrl.build(probe, current.startMs, now)
                }
                val timeFmt = java.text.SimpleDateFormat(
                    "h:mm a", java.util.Locale.getDefault()
                )
                fun span(p: EpgProgramme) =
                    "${timeFmt.format(java.util.Date(p.startMs))}–" +
                        timeFmt.format(java.util.Date(p.endMs))
                if (current != null) {
                    lines += getString(R.string.info_now, current.title) + " (${span(current)})"
                    if (current.description.isNotBlank()) {
                        val desc = current.description.trim()
                        lines += if (desc.length > 160) desc.take(160) + "…" else desc
                    }
                }
                if (next != null) {
                    lines += getString(R.string.info_next, next.title) + " (${span(next)})"
                }
            }
        } catch (e: Exception) {
        }
        infoText.text = lines.joinToString("\n")
        restartButton.visibility =
            if (restartUrl != null) View.VISIBLE else View.GONE
    }

    /** Replays the current programme from its start, when catch-up exists. */
    private fun restartPlayback() {
        try {
            val probe = Channel(
                name = channelName,
                url = liveUrl,
                group = channelGroup,
                logo = null,
                provider = channelProvider,
                tvgId = channelTvgId,
                number = channelNumber,
                kind = channelKind,
                catchupSource = catchupSource,
                catchupHours = catchupHours
            )
            val programmes = EpgStore.findFor(probe)
            val now = System.currentTimeMillis()
            val current = programmes?.let { EpgMatcher.currentAt(it, now) }
            val url = current?.let { CatchupUrl.build(probe, it.startMs, now) }
            if (url == null) {
                Toast.makeText(this, R.string.guide_no_catchup, Toast.LENGTH_SHORT).show()
                return
            }
            switchTo(probe.copy(url = url), liveUrlForChannel = liveUrl)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.guide_no_catchup, Toast.LENGTH_SHORT).show()
        }
    }

    /** "#12 • ESPN" when the provider supplies a number, else the name. */
    private fun displayName(): String =
        if (channelNumber != null) "#$channelNumber • $channelName" else channelName

    /** "M:SS" or "H:MM:SS" for the resume toast. */
    private fun formatPosition(positionMs: Long): String {
        val totalSec = positionMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            "$hours:%02d:%02d".format(minutes, seconds)
        } else {
            "$minutes:%02d".format(seconds)
        }
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
                    params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
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
