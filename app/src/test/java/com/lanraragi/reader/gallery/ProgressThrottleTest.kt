package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {

    private var now = 0L
    private val throttle = ProgressThrottle(minIntervalMs = 100, clock = { now })

    @Test
    fun firstProgressIsEmitted_thenNothingWithinTheInterval() {
        assertTrue(throttle.shouldEmit(0.01f))
        now = 50
        assertFalse(throttle.shouldEmit(0.40f))
        now = 100
        assertTrue(throttle.shouldEmit(0.41f))
    }

    @Test
    fun samePercentIsNeverRepeated() {
        assertTrue(throttle.shouldEmit(0.500f))
        now = 1_000
        assertFalse(throttle.shouldEmit(0.505f))
    }

    @Test
    fun completionIsAlwaysEmitted() {
        assertTrue(throttle.shouldEmit(0.10f))
        now = 1
        assertTrue(throttle.shouldEmit(1f))
    }

    @Test
    fun a6MbPageIn64KbChunksEmitsAFewUpdatesInsteadOfNinetySix() {
        val total = 6L * 1024 * 1024
        var emitted = 0
        var read = 0L
        while (read < total) {
            read += 65_536
            now += 2 // 64 KB every 2 ms on a fast LAN
            if (throttle.shouldEmit(read.toFloat() / total)) emitted++
        }
        // ~192 ms of transfer: the first chunk, one at ~100 ms, and 100 %.
        assertEquals(3, emitted)
    }
}
