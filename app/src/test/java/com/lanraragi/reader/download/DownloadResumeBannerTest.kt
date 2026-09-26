package com.lanraragi.reader.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadResumeBannerTest {

    /** Stands in for the prefs-backed store: its contents outlive the in-memory banner state. */
    private class FakeStore : DownloadResumeBanner.InterruptedStore {
        var saved: Set<String> = emptySet()
        override fun load(): Set<String> = saved
        override fun save(arcids: Set<String>) { saved = arcids.toSet() }
    }

    private val store = FakeStore()
    private lateinit var originalStore: DownloadResumeBanner.InterruptedStore

    @Before
    fun setUp() {
        originalStore = DownloadResumeBanner.interruptedStore
        DownloadResumeBanner.interruptedStore = store
    }

    @After
    fun tearDown() {
        DownloadResumeBanner.clear()
        DownloadResumeBanner.interruptedStore = originalStore
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

    // ── A47: downloads interrupted by process death ──

    @Test
    fun interrupted_isOfferedWithItsArcidsAndConsumedOnce() {
        DownloadResumeBanner.markInterrupted(listOf("a", "b"))

        assertEquals(
            DownloadResumeBanner.Snapshot.Interrupted(listOf("a", "b"), 2),
            DownloadResumeBanner.consume(),
        )
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun interrupted_isReadFromTheStoreSoItSurvivesProcessDeath() {
        // Recorded by an earlier process that died before the user came back.
        store.saved = setOf("a")

        assertEquals(DownloadResumeBanner.Snapshot.Interrupted(listOf("a"), 1), DownloadResumeBanner.consume())
        assertEquals(emptySet<String>(), store.saved)
    }

    @Test
    fun markResumed_dropsAnInterruptedArcid() {
        DownloadResumeBanner.markInterrupted(listOf("a", "b"))
        DownloadResumeBanner.markResumed("a")

        assertEquals(DownloadResumeBanner.Snapshot.Interrupted(listOf("b"), 1), DownloadResumeBanner.consume())
    }

    @Test
    fun timedOut_isShownFirstAndInterruptedWaitsForTheNextForeground() {
        DownloadResumeBanner.markInterrupted(listOf("a"))
        DownloadResumeBanner.markTimedOut("b", "Title B")

        assertTrue(DownloadResumeBanner.consume() is DownloadResumeBanner.Snapshot.TimedOut)
        assertEquals(DownloadResumeBanner.Snapshot.Interrupted(listOf("a"), 1), DownloadResumeBanner.consume())
    }
}
