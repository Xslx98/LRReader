package com.lanraragi.reader.reading

import com.lanraragi.framework.lib.glgallery.GalleryView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the online readers download ahead (item 20): single pages keep the
 * old window, two-page spreads (any known parity, a wide page alone included)
 * double it because each turn moves on two pages.
 */
class GalleryProvider2PagesAheadTest {

    @Test
    fun `single pages keep the window, spreads double it`() {
        assertEquals(5, GalleryProvider2.pagesAhead(5, GalleryView.PARITY_UNKNOWN))
        assertEquals(10, GalleryProvider2.pagesAhead(5, 0))
        assertEquals(10, GalleryProvider2.pagesAhead(5, 1))
    }
}
