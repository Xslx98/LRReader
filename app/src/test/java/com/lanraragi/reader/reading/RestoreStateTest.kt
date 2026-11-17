package com.lanraragi.reader.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's start position and restore suppression, one value for all
 * three providers. Owner's beta diagnostics (2026-11-01, downloaded archive,
 * vertical mode): on every reopen the layout reported page 0 for a moment
 * before the saved page, the provider took that for the user's navigation,
 * cancelled the start-position restore and saved page 0. Only a page change
 * after a touch or key counts as navigation.
 */
class RestoreStateTest {

    private fun opened(saved: Int, override: Int = RestoreState.NO_OVERRIDE) =
        RestoreState(startPage = saved, override = override).started()

    @Test
    fun `a page reported before any touch or key is not the user's`() {
        val s = opened(saved = 5).pageShown(0)

        assertFalse(s.userNavigated)
        assertEquals("the restore still jumps", 12, s.resolve(page = 12, fraction = 0f))
        assertEquals("the shown page is the position", 0, s.startPage)
    }

    @Test
    fun `a page change after a touch is the user's and stops the restore`() {
        val s = opened(saved = 5).interacted().pageShown(6)

        assertTrue(s.userNavigated)
        assertNull(s.resolve(page = 12, fraction = 0.5f))
    }

    @Test
    fun `reporting the baseline page after a touch is not navigation`() {
        assertFalse(opened(saved = 5).interacted().pageShown(5).userNavigated)
    }

    @Test
    fun `input from an earlier session does not carry into a new start`() {
        val s = RestoreState(startPage = 5).interacted().started()

        assertFalse(s.userInteracted)
        assertFalse(s.pageShown(9).userNavigated)
    }

    @Test
    fun `an explicit open page other than the saved one wins over the restore`() {
        assertTrue(opened(saved = 3, override = 7).userNavigated)
        assertFalse(opened(saved = 7, override = 7).userNavigated)
        assertFalse(opened(saved = 3).userNavigated)
    }

    @Test
    fun `the entry page is the explicit open page, else the saved one`() {
        assertEquals(7, opened(saved = 3, override = 7).entryPage)
        assertEquals(3, opened(saved = 3).entryPage)
    }

    @Test
    fun `the baseline is the page the reader opened on, not what the reconcile moved it to`() {
        val s = opened(saved = 5).reconciled(12)

        assertEquals(12, s.startPage)
        assertEquals(5, s.baseline)
        assertFalse("the restore's own jump is not the user's", s.interacted().pageShown(5).userNavigated)
    }

    @Test
    fun `a reconcile after the user navigated does not move the position`() {
        val s = opened(saved = 5).interacted().pageShown(8).reconciled(12)

        assertEquals(8, s.startPage)
    }

    @Test
    fun `nothing to restore at the top of the first page`() {
        assertNull(opened(saved = 0).resolve(page = 0, fraction = 0f))
        assertEquals(0, opened(saved = 0).resolve(page = 0, fraction = 0.3f))
    }
}
