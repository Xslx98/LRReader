package com.hippo.ehviewer.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for audit 2026-08-04 #20: the auto-read timer scheduled the
 * first page turn after the configured interval but every subsequent turn at
 * twice that interval (`waitTime = initialDelay * 2`), so a user who chose
 * "30 s" actually got a 60 s steady-state pace. The schedule must honor the
 * configured interval for both the initial delay and the repeat period.
 */
class GalleryInputHandlerAutoReadTimingTest {

    @Test
    fun `first turn fires after the configured interval`() {
        val (initialDelay, _) = GalleryInputHandler.autoReadTimings(30L)
        assertEquals(30L, initialDelay)
    }

    @Test
    fun `steady-state period equals the configured interval, not double`() {
        val (_, period) = GalleryInputHandler.autoReadTimings(30L)
        assertEquals(30L, period)
    }
}
