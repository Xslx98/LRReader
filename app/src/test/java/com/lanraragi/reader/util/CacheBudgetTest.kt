package com.lanraragi.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit C35: fixed disk budgets shrink to the cache quota, never grow, never vanish. */
class CacheBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun unknown_quota_keeps_defaults() {
        assertEquals(500 * mb, CacheBudget.scale(500 * mb, 64 * mb, 0L))
        assertEquals(200 * mb, CacheBudget.scale(200 * mb, 16 * mb, -1L))
    }

    @Test
    fun ample_quota_keeps_defaults_and_never_grows() {
        assertEquals(500 * mb, CacheBudget.scale(500 * mb, 64 * mb, 8192 * mb))
        assertEquals(200 * mb, CacheBudget.scale(200 * mb, 16 * mb, 2048 * mb))
    }

    @Test
    fun small_quota_shrinks_all_budgets_by_the_same_factor() {
        val quota = 510 * mb // 0.9 x 510 = 459 MB for a 1020 MB default total: factor 0.45
        val reader = CacheBudget.scale(500 * mb, 64 * mb, quota)
        val http = CacheBudget.scale(200 * mb, 16 * mb, quota)
        val thumbs = CacheBudget.scale(320 * mb, 32 * mb, quota)
        assertEquals(225 * mb, reader)
        assertEquals(90 * mb, http)
        assertEquals(144 * mb, thumbs)
        assertTrue(reader + http + thumbs <= quota)
    }

    @Test
    fun tiny_quota_stops_at_the_floor() {
        assertEquals(64 * mb, CacheBudget.scale(500 * mb, 64 * mb, 50 * mb))
        assertEquals(16 * mb, CacheBudget.scale(200 * mb, 16 * mb, 50 * mb))
    }
}
