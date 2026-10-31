package com.lanraragi.framework.widget.lockpattern

import com.lanraragi.framework.widget.lockpattern.LockPatternView.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored pattern encoding: one char per cell holding `row * 3 + column`.
 * Patterns saved by earlier versions must keep unlocking, so these strings
 * may never change.
 */
class LockPatternUtilsTest {

    private fun cells(vararg indexes: Int): List<Cell> = indexes.map { Cell.of(it / 3, it % 3) }

    @Test
    fun a_pattern_serializes_to_one_row_major_index_per_cell() {
        assertEquals("\u0000\u0001\u0002\u0005", LockPatternUtils.patternToString(cells(0, 1, 2, 5)))
        assertEquals("\u0008\u0004\u0000", LockPatternUtils.patternToString(cells(8, 4, 0)))
    }

    @Test
    fun a_nine_cell_pattern_round_trips_to_the_cached_cells() {
        val pattern = cells(0, 1, 2, 5, 4, 3, 6, 7, 8)
        val string = LockPatternUtils.patternToString(pattern)
        assertEquals("\u0000\u0001\u0002\u0005\u0004\u0003\u0006\u0007\u0008", string)

        val decoded = LockPatternUtils.stringToPattern(string)
        assertEquals(pattern.size, decoded.size)
        pattern.indices.forEach { assertSame(pattern[it], decoded[it]) }
    }

    @Test
    fun a_stored_string_decodes_to_its_cells() {
        val decoded = LockPatternUtils.stringToPattern("\u0006\u0004\u0002")
        assertEquals(listOf(6, 4, 2), decoded.map { it.row * 3 + it.column })
    }

    @Test
    fun null_and_empty_patterns_map_to_the_empty_string() {
        assertEquals("", LockPatternUtils.patternToString(null))
        assertEquals("", LockPatternUtils.patternToString(emptyList()))
        assertTrue(LockPatternUtils.stringToPattern("").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun an_out_of_range_byte_does_not_decode() {
        LockPatternUtils.stringToPattern("\u0000\u0009")
    }

    @Test
    fun validity_takes_two_to_nine_cells() {
        assertFalse(LockPatternUtils.isPatternVaild(""))
        assertFalse(LockPatternUtils.isPatternVaild("\u0000"))
        assertTrue(LockPatternUtils.isPatternVaild("\u0000\u0001"))
        assertTrue(LockPatternUtils.isPatternVaild("\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008"))
        assertFalse(LockPatternUtils.isPatternVaild("\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0000"))
    }

    @Test
    fun validity_takes_bytes_zero_to_eight_only() {
        assertTrue(LockPatternUtils.isPatternVaild("\u0000\u0008"))
        assertFalse(LockPatternUtils.isPatternVaild("\u0000\u0009"))
        assertFalse(LockPatternUtils.isPatternVaild("01"))
        assertFalse(LockPatternUtils.isPatternVaild("\u0000é"))
    }
}
