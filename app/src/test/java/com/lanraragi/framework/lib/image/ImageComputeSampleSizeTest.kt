package com.lanraragi.framework.lib.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-math contract for [Image.computeSampleSize]: the largest integer
 * sample that keeps BOTH decoded dimensions >= the target (quality floor),
 * never below 1, with non-positive targets treated as "no sampling".
 */
class ImageComputeSampleSizeTest {

    @Test
    fun `source twice the target samples by two`() {
        // 1000x1414 thumb into a 336x470 cell: min(2, 3) = 2
        assertEquals(2, Image.computeSampleSize(1000, 1414, 336, 470))
    }

    @Test
    fun `source under twice the target keeps full resolution`() {
        assertEquals(1, Image.computeSampleSize(500, 707, 336, 470))
    }

    @Test
    fun `source smaller than target keeps full resolution`() {
        assertEquals(1, Image.computeSampleSize(200, 280, 336, 470))
    }

    @Test
    fun `sample is limited by the tighter dimension`() {
        // width allows 8x but height only 2x; both dims must stay >= target
        assertEquals(2, Image.computeSampleSize(4000, 1000, 500, 500))
    }

    @Test
    fun `large source samples aggressively`() {
        assertEquals(8, Image.computeSampleSize(4000, 6000, 500, 750))
    }

    @Test
    fun `exact multiple boundary`() {
        assertEquals(2, Image.computeSampleSize(672, 940, 336, 470))
    }

    @Test
    fun `zero target means no sampling`() {
        assertEquals(1, Image.computeSampleSize(1000, 1414, 0, 0))
    }

    @Test
    fun `negative target means no sampling`() {
        assertEquals(1, Image.computeSampleSize(1000, 1414, -1, 470))
    }

    // computeDecodeSampleSize (audit 2026-10-06c PERF-01): fit x multiplier only.

    @Test
    fun `screen-fit page decodes at full resolution`() {
        // 2400x3600 scan on a 1440x3200 phone: min(1, 1) = 1
        assertEquals(1, Image.computeDecodeSampleSize(2400, 3600, 0, 0, 1, 1440, 3200))
    }

    @Test
    fun `huge pixel count is bounded by the screen fit`() {
        // 14400x32000 on 1440x3200 -> sample 10 -> 1440x3200 decoded
        assertEquals(10, Image.computeDecodeSampleSize(14400, 32000, 0, 0, 1, 1440, 3200))
    }

    @Test
    fun `very tall page keeps full width (no pixel cap, user ruling)`() {
        assertEquals(1, Image.computeDecodeSampleSize(1440, 60000, 0, 0, 1, 1440, 3200))
    }

    @Test
    fun `oom retry multiplier is applied to the fit`() {
        assertEquals(2, Image.computeDecodeSampleSize(2400, 3600, 0, 0, 2, 1440, 3200))
        assertEquals(20, Image.computeDecodeSampleSize(14400, 32000, 0, 0, 2, 1440, 3200))
    }

    @Test
    fun `explicit target wins over the screen`() {
        assertEquals(2, Image.computeDecodeSampleSize(1000, 1414, 336, 470, 1, 1440, 3200))
        assertEquals(4, Image.computeDecodeSampleSize(1000, 1414, 336, 470, 2, 1440, 3200))
    }
}
