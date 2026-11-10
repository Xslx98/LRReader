package com.lanraragi.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page range the top bar shows: "3-4" while a two-page spread is on
 * screen, back to one page as soon as a single page is reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GallerySliderShownPagesTest {

    private val slider = GallerySliderController()
    private var shown = ""

    init {
        slider.onPageChanged = { index, last, size -> shown = "$index-$last/$size" }
        slider.size = 40
    }

    @Test
    fun `a spread reports both pages and the next single page reports one`() {
        slider.setShownPages(2, 3)
        assertEquals("2-3/40", shown)
        // The index notification that follows the spread one changes nothing.
        slider.currentIndex = 2
        assertEquals("2-3/40", shown)

        // Back to single pages (rotation, a wide page): only the index arrives.
        slider.currentIndex = 4
        assertEquals("4-4/40", shown)
    }

    @Test
    fun `turning back from a spread to a single page drops the old partner`() {
        slider.setShownPages(4, 5)
        slider.currentIndex = 3
        assertEquals("3-3/40", shown)
    }

    @Test
    fun `a spread that loses its partner reports the page alone`() {
        slider.setShownPages(2, 3)
        slider.setShownPages(2, 2)
        assertEquals("2-2/40", shown)
    }
}
