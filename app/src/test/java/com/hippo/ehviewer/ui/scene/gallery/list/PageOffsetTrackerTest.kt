package com.hippo.ehviewer.ui.scene.gallery.list

import org.junit.Assert.assertEquals
import org.junit.Test

class PageOffsetTrackerTest {

    private val tracker = PageOffsetTracker(nominalPageSize = 50)

    @Test
    fun pageZero_startsAtOffsetZero() {
        assertEquals(0, tracker.offsetFor(0))
    }

    @Test
    fun unchainedPage_fallsBackToNominalArithmetic() {
        // No load recorded yet (e.g. state restore mid-list): behave like the
        // pre-tracker fixed-size math rather than guessing.
        assertEquals(100, tracker.offsetFor(2))
    }

    @Test
    fun recordLoaded_chainsActualNextOffset() {
        // Server returned 20 rows for page 0 (archives_per_page=20): page 1
        // must start at offset 20, not 50 — offset 50 would skip rows 20-49.
        tracker.recordLoaded(page = 0, nextOffset = 20)
        assertEquals(20, tracker.offsetFor(1))
    }

    @Test
    fun chainSurvivesMultiplePages() {
        tracker.recordLoaded(page = 0, nextOffset = 20)
        tracker.recordLoaded(page = 1, nextOffset = 40)
        assertEquals(40, tracker.offsetFor(2))
    }

    @Test
    fun reset_dropsChainedOffsets() {
        tracker.recordLoaded(page = 0, nextOffset = 20)
        tracker.reset()
        assertEquals(50, tracker.offsetFor(1))
    }

    @Test
    fun recordLoaded_nullNextOffset_leavesChainUntouched() {
        // null = no more rows; ContentLayout won't request the next page, and
        // a later retry of the same page must not be disturbed.
        tracker.recordLoaded(page = 0, nextOffset = 20)
        tracker.recordLoaded(page = 1, nextOffset = null)
        assertEquals(20, tracker.offsetFor(1))
    }

    @Test
    fun rerecord_overwritesPriorChain() {
        // A refreshed page 0 may return a different count (server setting
        // changed, rows added): the newer chain wins.
        tracker.recordLoaded(page = 0, nextOffset = 20)
        tracker.recordLoaded(page = 0, nextOffset = 25)
        assertEquals(25, tracker.offsetFor(1))
    }
}
