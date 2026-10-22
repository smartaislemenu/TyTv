package com.tytv.app.settings

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Buffer-size presets. The durations map to the player's load control:
 * more buffering = fewer stalls on slow connections, slower channel
 * switching; less buffering = snappier switching, less stall protection.
 * Matches the web app's presets (Low 4s / Balanced 10s / High 30s /
 * Extra 60s) so the setting feels the same in both versions.
 */
enum class BufferSize(
    val label: String,
    val shortDescription: String,
    val description: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
) {
    LOW(
        "Low", "4s ahead",
        "About 4 seconds buffered ahead. Snappiest channel switching; may stall on slow connections.",
        4000, 4000, 1000, 2000
    ),
    BALANCED(
        "Balanced", "10s ahead",
        "About 10 seconds buffered ahead. Good balance of switching speed and stall protection.",
        10000, 15000, 2000, 3000
    ),
    HIGH(
        "High", "30s ahead",
        "About 30 seconds buffered ahead. Helps prevent stalls and freezes on slower or uneven connections.",
        30000, 30000, 2500, 5000
    ),
    EXTRA(
        "Extra", "60s ahead",
        "About 60 seconds buffered ahead. Maximum stall protection; slowest channel switching.",
        60000, 60000, 2500, 5000
    );
}

object BufferSettings {
    private const val PREFS = "tytv_prefs"
    private const val KEY_BUFFER_SIZE = "buffer_size"

    fun get(context: Context): BufferSize {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BUFFER_SIZE, null)
        return try {
            if (name == null) BufferSize.BALANCED else BufferSize.valueOf(name)
        } catch (e: IllegalArgumentException) {
            BufferSize.BALANCED
        }
    }

    fun set(context: Context, size: BufferSize) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BUFFER_SIZE, size.name).apply()
    }

    /** Builds the ExoPlayer load control for the given preset. */
    fun loadControl(size: BufferSize): LoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                size.minBufferMs,
                size.maxBufferMs,
                size.bufferForPlaybackMs,
                size.bufferForPlaybackAfterRebufferMs
            )
            .build()
    }
}
