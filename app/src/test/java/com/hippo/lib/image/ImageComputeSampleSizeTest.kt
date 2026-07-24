package com.hippo.lib.image

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
}
