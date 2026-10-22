package com.tytv.app

import com.tytv.app.settings.BufferSize
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferSettingsTest {

    @Test
    fun presetsHaveSaneDurations() {
        assertEquals(4000, BufferSize.LOW.minBufferMs)
        assertEquals(10000, BufferSize.BALANCED.minBufferMs)
        assertEquals(30000, BufferSize.HIGH.minBufferMs)
        assertEquals(60000, BufferSize.EXTRA.minBufferMs)
        for (size in BufferSize.values()) {
            assertEquals(true, size.maxBufferMs >= size.minBufferMs)
            assertEquals(true, size.bufferForPlaybackMs > 0)
            assertEquals(true, size.bufferForPlaybackAfterRebufferMs > 0)
        }
    }
}
