package com.lanraragi.framework.lib.image

import com.lanraragi.framework.lib.glgallery.GalleryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader page sampling by scale mode (owner decision 2026-10-06, audits R1 /
 * PERF-02): [Image.readerSampleSize] keeps the decoded page at or above what
 * the mode shows at 1x zoom, in both screen orientations, and no larger.
 */
class ImageReaderSampleSizeTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `fit samples by the larger ratio (scan on a phone)`() {
        // Decoded 1400x2000: portrait fit shows 1080x1543, landscape 756x1080.
        // The old min-ratio rule kept 2800x4000 (44.8 MB instead of 11.2 MB).
        assertEquals(2, Image.readerSampleSize(2800, 4000, w, h, PageFit.FIT))
    }

    @Test
    fun `fit sample does not depend on the current orientation`() {
        assertEquals(
            Image.readerSampleSize(2800, 4000, w, h, PageFit.FIT),
            Image.readerSampleSize(2800, 4000, h, w, PageFit.FIT)
        )
        assertEquals(
            Image.readerSampleSize(14400, 3200, w, h, PageFit.FIT),
            Image.readerSampleSize(14400, 3200, h, w, PageFit.FIT)
        )
    }

    @Test
    fun `wide page in fit is bounded by the landscape fit`() {
        // 14400x3200 decoded at sample 1 was 184 MB. Sample 6 -> 2400x533:
        // full width when the phone is turned to landscape, about 5 MB.
        val sample = Image.readerSampleSize(14400, 3200, w, h, PageFit.FIT)
        assertEquals(6, sample)
        assertTrue(14400 / sample >= h)
    }

    @Test
    fun `tall strip in fit width keeps full width`() {
        // 1000x30000 webtoon strip: narrower than the screen, so never sampled.
        assertEquals(1, Image.readerSampleSize(1000, 30000, w, h, PageFit.FIT_WIDTH))
        assertEquals(1, Image.readerSampleSize(1440, 60000, w, h, PageFit.FIT_WIDTH))
    }

    @Test
    fun `fit width samples by the width against the longer screen side`() {
        // 2160x3000: landscape fit width shows 2400 px wide, so it stays full.
        assertEquals(1, Image.readerSampleSize(2160, 3000, w, h, PageFit.FIT_WIDTH))
        // 4800x6000 -> 2400 px wide, enough for either orientation.
        assertEquals(2, Image.readerSampleSize(4800, 6000, w, h, PageFit.FIT_WIDTH))
        assertEquals(6, Image.readerSampleSize(14400, 3200, w, h, PageFit.FIT_WIDTH))
    }

    @Test
    fun `fit height samples by the height against the longer screen side`() {
        assertEquals(1, Image.readerSampleSize(2160, 3000, w, h, PageFit.FIT_HEIGHT))
        assertEquals(4, Image.readerSampleSize(4000, 10000, w, h, PageFit.FIT_HEIGHT))
        // A tall strip in fit height is shown 2400 px tall: heavily sampled.
        assertEquals(12, Image.readerSampleSize(1000, 30000, w, h, PageFit.FIT_HEIGHT))
    }

    @Test
    fun `origin keeps the old both-sides-above-the-screen rule`() {
        assertEquals(2, Image.readerSampleSize(5000, 6000, w, h, PageFit.ORIGIN))
        assertEquals(1, Image.readerSampleSize(14400, 3200, w, h, PageFit.ORIGIN))
        assertEquals(1, Image.readerSampleSize(2800, 4000, w, h, PageFit.ORIGIN))
    }

    @Test
    fun `no screen size disables sampling`() {
        for (fit in PageFit.entries) {
            assertEquals(1, Image.readerSampleSize(14400, 32000, 0, 0, fit))
        }
    }

    /**
     * Property over a grid of page sizes: the sample never shrinks a page below
     * its 1x display size in either orientation, and one more step would.
     */
    @Test
    fun `sample is the largest that keeps the 1x size in both orientations`() {
        val sizes = intArrayOf(300, 700, 1000, 1080, 1500, 2160, 2400, 2800, 4000, 5000, 9000, 14400, 30000)
        for (fit in listOf(PageFit.FIT, PageFit.FIT_WIDTH, PageFit.FIT_HEIGHT)) {
            for (sw in sizes) {
                for (sh in sizes) {
                    val sample = Image.readerSampleSize(sw, sh, w, h, fit)
                    val needed = listOf(w to h, h to w).maxOf { (vw, vh) -> displayScale(fit, sw, sh, vw, vh) }
                    // decoded side = source / sample must be >= source * displayScale
                    assertTrue("$fit ${sw}x$sh sample $sample", sample == 1 || sample * needed <= 1.0)
                    assertTrue("$fit ${sw}x$sh sample $sample not maximal", (sample + 1) * needed > 1.0)
                }
            }
        }
    }

    private fun displayScale(fit: PageFit, sw: Int, sh: Int, vw: Int, vh: Int): Double = when (fit) {
        PageFit.FIT -> minOf(vw.toDouble() / sw, vh.toDouble() / sh)
        PageFit.FIT_WIDTH -> vw.toDouble() / sw
        PageFit.FIT_HEIGHT -> vh.toDouble() / sh
        PageFit.ORIGIN -> 1.0
    }

    @Test
    fun `reading direction and scale mode map to the effective fit`() {
        val pager = GalleryView.LAYOUT_RIGHT_TO_LEFT
        assertEquals(PageFit.FIT, PageFit.of(pager, GalleryView.SCALE_FIT))
        assertEquals(PageFit.FIT_WIDTH, PageFit.of(GalleryView.LAYOUT_LEFT_TO_RIGHT, GalleryView.SCALE_FIT_WIDTH))
        assertEquals(PageFit.FIT_HEIGHT, PageFit.of(pager, GalleryView.SCALE_FIT_HEIGHT))
        assertEquals(PageFit.ORIGIN, PageFit.of(pager, GalleryView.SCALE_ORIGIN))
        assertEquals(PageFit.ORIGIN, PageFit.of(pager, GalleryView.SCALE_FIXED))
        // The top-to-bottom layout always shows pages at the view width.
        for (scale in intArrayOf(
            GalleryView.SCALE_FIT, GalleryView.SCALE_FIT_HEIGHT, GalleryView.SCALE_ORIGIN, GalleryView.SCALE_FIXED
        )) {
            assertEquals(PageFit.FIT_WIDTH, PageFit.of(GalleryView.LAYOUT_TOP_TO_BOTTOM, scale))
        }
    }
}
