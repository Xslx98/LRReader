package com.lanraragi.reader.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PageWindowTest {

    @Test
    fun `pageCount rounds a partial page up and has no pages for no items`() {
        assertEquals(0, PageWindow.pageCount(0, 50))
        assertEquals(1, PageWindow.pageCount(50, 50))
        assertEquals(2, PageWindow.pageCount(51, 50))
        assertEquals(11, PageWindow.pageCount(501, 50))
    }

    @Test
    fun `clampPage pulls a page past the end back to the last page`() {
        assertEquals(4, PageWindow.clampPage(9, 4))
        assertEquals(1, PageWindow.clampPage(0, 4))
        assertEquals(1, PageWindow.clampPage(3, 0))
        assertEquals(3, PageWindow.clampPage(3, 4))
    }

    @Test
    fun `visiblePages centres on the page and slides at both ends`() {
        assertEquals(1..5, PageWindow.visiblePages(1, 11, 5))
        assertEquals(1..5, PageWindow.visiblePages(3, 11, 5))
        assertEquals(4..8, PageWindow.visiblePages(6, 11, 5))
        assertEquals(7..11, PageWindow.visiblePages(9, 11, 5))
        assertEquals(7..11, PageWindow.visiblePages(11, 11, 5))
    }

    @Test
    fun `visiblePages shows every page when there are fewer than the tips`() {
        assertEquals(1..3, PageWindow.visiblePages(2, 3, 5))
        assertEquals(true, PageWindow.visiblePages(1, 0, 5).isEmpty())
    }
}
