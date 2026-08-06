package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TankPageMap] — the mutable global(0-indexed) ↔ (member, local
 * 0-indexed) mapping behind the tank composite reader. Built from metadata
 * pagecounts, corrected in place when real file lists disagree, remapped
 * when a member turns out to be deleted server-side.
 */
class TankPageMapTest {

    private fun map(vararg pagecounts: Int) = TankPageMap(pagecounts.toList())

    // ── construction & totals ────────────────────────────────────────

    @Test
    fun `total is the sum of member pagecounts`() {
        assertEquals(45, map(20, 25).total)
    }

    @Test
    fun `negative metadata pagecounts are clamped to zero`() {
        assertEquals(10, map(-3, 10).total)
    }

    @Test
    fun `memberCount reflects construction`() {
        assertEquals(3, map(1, 2, 3).memberCount)
    }

    // ── locate: global0 → (member, local0) ───────────────────────────

    @Test
    fun `locate maps first page of first member`() {
        assertEquals(0 to 0, map(20, 25).locate(0))
    }

    @Test
    fun `locate maps last page of first member`() {
        assertEquals(0 to 19, map(20, 25).locate(19))
    }

    @Test
    fun `locate maps first page of second member`() {
        assertEquals(1 to 0, map(20, 25).locate(20))
    }

    @Test
    fun `locate maps last page of tank`() {
        assertEquals(1 to 24, map(20, 25).locate(44))
    }

    @Test
    fun `locate skips zero-page members`() {
        // member 1 has 0 pages: global 20 must land on member 2 page 0
        assertEquals(2 to 0, map(20, 0, 5).locate(20))
    }

    @Test
    fun `locate out of range returns null`() {
        assertNull(map(20).locate(20))
        assertNull(map(20).locate(-1))
    }

    // ── globalOf: (member, local0) → global0 ─────────────────────────

    @Test
    fun `globalOf inverts locate`() {
        val m = map(20, 25, 7)
        for (g in 0 until m.total) {
            val (member, local) = m.locate(g)!!
            assertEquals(g, m.globalOf(member, local))
        }
    }

    // ── correct: file list disagrees with metadata ───────────────────

    @Test
    fun `correct shrinks a member and shifts later members`() {
        val m = map(20, 25)
        val changed = m.correct(memberIndex = 0, actualPageCount = 18)
        assertTrue(changed)
        assertEquals(43, m.total)
        assertEquals(1 to 0, m.locate(18))
        assertEquals(0 to 17, m.locate(17))
    }

    @Test
    fun `correct grows a member`() {
        val m = map(20, 25)
        assertTrue(m.correct(0, 22))
        assertEquals(47, m.total)
        assertEquals(0 to 21, m.locate(21))
        assertEquals(1 to 0, m.locate(22))
    }

    @Test
    fun `correct with matching count is a no-op`() {
        val m = map(20, 25)
        assertFalse(m.correct(0, 20))
        assertEquals(45, m.total)
    }

    @Test
    fun `pageCountOf reflects corrections`() {
        val m = map(20, 25)
        m.correct(1, 30)
        assertEquals(30, m.pageCountOf(1))
        assertEquals(20, m.pageCountOf(0))
    }

    // ── removeMember: confirmed-deleted skip ─────────────────────────

    @Test
    fun `removeMember drops its pages and shifts the rest`() {
        val m = map(10, 20, 30)
        m.removeMember(1)
        assertEquals(2, m.memberCount)
        assertEquals(40, m.total)
        // old member 2 is now member 1, starting right after member 0
        assertEquals(1 to 0, m.locate(10))
        assertEquals(1 to 29, m.locate(39))
    }

    @Test
    fun `removeMember of the only member empties the map`() {
        val m = map(10)
        m.removeMember(0)
        assertEquals(0, m.memberCount)
        assertEquals(0, m.total)
        assertNull(m.locate(0))
    }

    // ── memberStart for prefetch math ────────────────────────────────

    @Test
    fun `memberStart returns the global index of a member's first page`() {
        val m = map(20, 25, 7)
        assertEquals(0, m.memberStart(0))
        assertEquals(20, m.memberStart(1))
        assertEquals(45, m.memberStart(2))
    }
}
