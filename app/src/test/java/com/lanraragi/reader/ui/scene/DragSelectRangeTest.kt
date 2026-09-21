package com.lanraragi.reader.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/** Matrix from spec 2026-09-22-drag-select §6 for the pure range arithmetic. */
class DragSelectRangeTest {

    private val all = { _: Int -> true }

    @Test
    fun `forward drag checks anchor through cursor inclusive`() {
        val checked = DragSelectRange.apply(anchor = 3, cursor = 9, preDrag = setOf(3), itemCount = 30, canSelect = all)
        assertEquals((3..9).toSet(), checked)
    }

    @Test
    fun `backward drag past the anchor checks the range above it`() {
        val checked = DragSelectRange.apply(anchor = 5, cursor = 1, preDrag = setOf(5), itemCount = 30, canSelect = all)
        assertEquals((1..5).toSet(), checked)
    }

    @Test
    fun `sliding back toward the anchor drops rows only the drag added`() {
        val wide = DragSelectRange.apply(3, 9, setOf(3), 30, all)
        val narrow = DragSelectRange.apply(3, 5, setOf(3), 30, all)
        assertEquals((3..9).toSet(), wide)
        assertEquals((3..5).toSet(), narrow)
        assertEquals(DragSelectRange.Diff(check = emptyList(), uncheck = listOf(6, 7, 8, 9)), DragSelectRange.diff(wide, narrow))
    }

    @Test
    fun `crossing over the anchor flips the range side and keeps the anchor`() {
        val below = DragSelectRange.apply(5, 8, setOf(5), 30, all)
        val above = DragSelectRange.apply(5, 2, setOf(5), 30, all)
        assertEquals((5..8).toSet(), below)
        assertEquals((2..5).toSet(), above)
    }

    @Test
    fun `rows checked before the drag survive a back-slide`() {
        val preDrag = setOf(3, 7, 20)
        val wide = DragSelectRange.apply(3, 9, preDrag, 30, all)
        val narrow = DragSelectRange.apply(3, 4, preDrag, 30, all)
        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9, 20), wide)
        assertEquals(setOf(3, 4, 7, 20), narrow)
    }

    @Test
    fun `unselectable rows are passed over but never checked`() {
        val checked = DragSelectRange.apply(1, 6, setOf(1), 30) { it != 3 && it != 4 }
        assertEquals(setOf(1, 2, 5, 6), checked)
    }

    @Test
    fun `range is clipped to the adapter bounds`() {
        assertEquals((0..2).toSet(), DragSelectRange.apply(2, -5, setOf(2), 10, all))
        assertEquals((7..9).toSet(), DragSelectRange.apply(7, 40, setOf(7), 10, all))
        assertEquals(setOf(1), DragSelectRange.apply(1, 5, setOf(1), 0, all))
    }

    @Test
    fun `diff lists what to check and uncheck in ascending order`() {
        val d = DragSelectRange.diff(current = setOf(9, 4, 5), target = setOf(4, 6, 2))
        assertEquals(listOf(2, 6), d.check)
        assertEquals(listOf(5, 9), d.uncheck)
    }
}
