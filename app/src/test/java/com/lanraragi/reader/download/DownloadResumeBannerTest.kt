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
        var writes = 0
        override fun load(): Set<String> = saved
        override fun save(arcids: Set<String>) { saved = arcids.toSet(); writes++ }
    }

    @Test
    fun markResumed_batch_clearsEveryEntryWithOneStoreWrite() {
        DownloadResumeBanner.markInterrupted(listOf("a", "b", "c", "d"))
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.TIMED_OUT)
        store.writes = 0

        DownloadResumeBanner.markResumed(listOf("a", "b", "c", "x"))

        assertEquals(1, store.writes)
        assertEquals(setOf("d"), store.saved)
        assertEquals(DownloadResumeBanner.Snapshot.Interrupted(listOf("d"), 1), DownloadResumeBanner.consume())
    }

    @Test
    fun markResumed_batch_withNoInterruptedArcid_doesNotWrite() {
        DownloadResumeBanner.markInterrupted(listOf("a"))
        store.writes = 0

        DownloadResumeBanner.markResumed(listOf("x", "y"))

        assertEquals(0, store.writes)
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
    fun storageFull_takesPrecedenceAndIsClearedByResume() {
        DownloadResumeBanner.mark("t", DownloadResumeBanner.Reason.TIMED_OUT)
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.STORAGE_FULL)
        DownloadResumeBanner.mark("b", DownloadResumeBanner.Reason.STORAGE_FULL)
        DownloadResumeBanner.markResumed("b")

        assertEquals(DownloadResumeBanner.Snapshot.StorageFull(listOf("a"), 1), DownloadResumeBanner.consume())
        // One-shot: everything was consumed with it.
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun consume_none_whenNothingRecorded() {
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun consume_paused_whenOnlyPaused() {
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.PAUSED)
        DownloadResumeBanner.mark("b", DownloadResumeBanner.Reason.PAUSED)
        val s = DownloadResumeBanner.consume()
        assertEquals(DownloadResumeBanner.Snapshot.Paused(2), s)
        // consume clears
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun markResumed_removesFromPaused() {
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.PAUSED)
        DownloadResumeBanner.markResumed("a")
        assertEquals(DownloadResumeBanner.Snapshot.None, DownloadResumeBanner.consume())
    }

    @Test
    fun timedOut_takesPrecedenceAndCarriesArcids() {
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.PAUSED)
        DownloadResumeBanner.mark("b", DownloadResumeBanner.Reason.TIMED_OUT)
        val s = DownloadResumeBanner.consume()
        assertTrue(s is DownloadResumeBanner.Snapshot.TimedOut)
        s as DownloadResumeBanner.Snapshot.TimedOut
        assertEquals(listOf("b"), s.arcids)
        assertEquals(1, s.count)
    }

    @Test
    fun markTimedOut_clearsPausedForSameArcid() {
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.PAUSED)
        DownloadResumeBanner.mark("a", DownloadResumeBanner.Reason.TIMED_OUT)
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
        DownloadResumeBanner.mark("b", DownloadResumeBanner.Reason.TIMED_OUT)

        assertTrue(DownloadResumeBanner.consume() is DownloadResumeBanner.Snapshot.TimedOut)
        assertEquals(DownloadResumeBanner.Snapshot.Interrupted(listOf("a"), 1), DownloadResumeBanner.consume())
    }

    @Test
    fun systemLimited_comesAfterStorageFull_beforeTimedOut_andIsClearedByResume() {
        DownloadResumeBanner.mark("t", DownloadResumeBanner.Reason.TIMED_OUT)
        DownloadResumeBanner.mark("s", DownloadResumeBanner.Reason.SYSTEM_LIMITED)
        DownloadResumeBanner.mark("r", DownloadResumeBanner.Reason.SYSTEM_LIMITED)
        DownloadResumeBanner.markResumed("r")
        assertEquals(DownloadResumeBanner.Snapshot.SystemLimited(listOf("s"), 1), DownloadResumeBanner.consume())

        DownloadResumeBanner.mark("s", DownloadResumeBanner.Reason.SYSTEM_LIMITED)
        DownloadResumeBanner.mark("f", DownloadResumeBanner.Reason.STORAGE_FULL)
        assertEquals(DownloadResumeBanner.Snapshot.StorageFull(listOf("f"), 1), DownloadResumeBanner.consume())
    }
}
