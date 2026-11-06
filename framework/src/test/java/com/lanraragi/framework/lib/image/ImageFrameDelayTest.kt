package com.lanraragi.framework.lib.image

import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit 2026-10-04 C13: animated pages follow the drawable's frame interval, capped at 60 fps. */
class ImageFrameDelayTest {

    @Test
    fun scheduledInterval_isUsed() {
        assertEquals(70, Image.frameDelay(70))
    }

    @Test
    fun tooFast_isClampedTo60fps() {
        assertEquals(16, Image.frameDelay(10))
        assertEquals(16, Image.frameDelay(-5))
    }

    @Test
    fun verySlow_isClampedSoTheLoopStaysResponsive() {
        assertEquals(1000, Image.frameDelay(5000))
    }
}
