package com.hippo.ehviewer.gallery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionTrackerTest {

    private val events = mutableListOf<ReadingSessionEnd>()
    private val tracker = ReadingSessionTracker(notify = events::add)

    @After
    fun tearDown() {
        ReadingSessionEvents.clearForTest()
    }

    @Test
    fun `stop without start emits nothing`() {
        tracker.stop(endPage = 5)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `start then stop emits one event carrying the session context`() {
        tracker.start(arcid = "a".repeat(40), serverProfileId = 7L, startPage = 3, pageCount = 20)
        tracker.stop(endPage = 12)

        assertEquals(1, events.size)
        val end = events.single()
        assertEquals("a".repeat(40), end.arcid)
        assertEquals(7L, end.serverProfileId)
        assertEquals(3, end.startPage)
        assertEquals(12, end.endPage)
        assertEquals(20, end.pageCount)
    }

    @Test
    fun `second stop advances the baseline so deltas never double-count`() {
        // Background the reader (stop), come back, read on, background again:
        // the second event must report only the pages read since the first stop.
        tracker.start(arcid = "b".repeat(40), serverProfileId = 1L, startPage = 0, pageCount = 30)
        tracker.stop(endPage = 10)
        tracker.stop(endPage = 25)

        assertEquals(2, events.size)
        assertEquals(0, events[0].startPage)
        assertEquals(10, events[0].endPage)
        assertEquals(10, events[1].startPage)
        assertEquals(25, events[1].endPage)
    }

    @Test
    fun `start again rebinds the tracker to the new archive`() {
        tracker.start(arcid = "c".repeat(40), serverProfileId = 1L, startPage = 0, pageCount = 10)
        tracker.stop(endPage = 4)
        tracker.start(arcid = "d".repeat(40), serverProfileId = 2L, startPage = 6, pageCount = 40)
        tracker.stop(endPage = 9)

        assertEquals(2, events.size)
        assertEquals("d".repeat(40), events[1].arcid)
        assertEquals(2L, events[1].serverProfileId)
        assertEquals(6, events[1].startPage)
        assertEquals(9, events[1].endPage)
        assertEquals(40, events[1].pageCount)
    }

    @Test
    fun `registry fans out to registered listeners and unregister stops delivery`() {
        val seen = mutableListOf<ReadingSessionEnd>()
        val listener = ReadingSessionEvents.Listener { seen.add(it) }
        ReadingSessionEvents.register(listener)

        val defaultTracker = ReadingSessionTracker()
        defaultTracker.start(arcid = "e".repeat(40), serverProfileId = 3L, startPage = 0, pageCount = 5)
        defaultTracker.stop(endPage = 5)
        assertEquals(1, seen.size)

        ReadingSessionEvents.unregister(listener)
        defaultTracker.stop(endPage = 5)
        assertEquals(1, seen.size)
    }
}
