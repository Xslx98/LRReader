package com.hippo.ehviewer.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeBannerTest {

    @After
    fun tearDown() {
        DownloadResumeBanner.clear()
    }

    @Test
    fun consume_none_whenNothingRecorded() {
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun consume_paused_whenOnlyPaused() {
        DownloadResumeBanner.markPaused("a", "Title A")
        DownloadResumeBanner.markPaused("b", "Title B")
        val s = DownloadResumeBanner.consume()
        assertEquals(DownloadResumeBanner.Snapshot.Paused(2), s)
        // consume clears
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun markResumed_removesFromPaused() {
        DownloadResumeBanner.markPaused("a", "Title A")
        DownloadResumeBanner.markResumed("a")
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun timedOut_takesPrecedenceAndCarriesArcids() {
        DownloadResumeBanner.markPaused("a", "Title A")
        DownloadResumeBanner.markTimedOut("b", "Title B")
        val s = DownloadResumeBanner.consume()
        assertTrue(s is DownloadResumeBanner.Snapshot.TimedOut)
        s as DownloadResumeBanner.Snapshot.TimedOut
        assertEquals(listOf("b"), s.arcids)
        assertEquals(1, s.count)
    }

    @Test
    fun markTimedOut_clearsPausedForSameArcid() {
        DownloadResumeBanner.markPaused("a", "Title A")
        DownloadResumeBanner.markTimedOut("a", "Title A")
        val s = DownloadResumeBanner.consume()
        assertTrue(s is DownloadResumeBanner.Snapshot.TimedOut)
        assertEquals(1, (s as DownloadResumeBanner.Snapshot.TimedOut).count)
    }
}
