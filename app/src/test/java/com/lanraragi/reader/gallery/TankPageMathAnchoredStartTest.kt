package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Start-page decision for a whole-tank session entered THROUGH a member
 * (spec 2026-09-21 §5: tank detail member row, history member row): saved
 * tank progress inside that member → let the provider restore it (-1);
 * anywhere else → the member's first global page.
 */
class TankPageMathAnchoredStartTest {

    private val counts = listOf(10, 5, 20)

    @Test
    fun `saved progress inside the anchor member restores`() {
        assertEquals(-1, TankPageMath.anchoredStart(counts, anchorIndex = 1, savedGlobal0 = 12))
        assertEquals(-1, TankPageMath.anchoredStart(counts, anchorIndex = 0, savedGlobal0 = 0))
    }

    @Test
    fun `saved progress elsewhere lands on the anchor member's first page`() {
        assertEquals(10, TankPageMath.anchoredStart(counts, anchorIndex = 1, savedGlobal0 = 3))
        assertEquals(15, TankPageMath.anchoredStart(counts, anchorIndex = 2, savedGlobal0 = 12))
        assertEquals(0, TankPageMath.anchoredStart(counts, anchorIndex = 0, savedGlobal0 = 30))
    }

    @Test
    fun `unknown page counts fall back to restore`() {
        assertEquals(-1, TankPageMath.anchoredStart(listOf(0, 0), anchorIndex = 1, savedGlobal0 = 0))
    }

    @Test
    fun `anchor out of range falls back to restore`() {
        assertEquals(-1, TankPageMath.anchoredStart(counts, anchorIndex = 7, savedGlobal0 = 0))
        assertEquals(-1, TankPageMath.anchoredStart(counts, anchorIndex = -1, savedGlobal0 = 0))
    }
}
