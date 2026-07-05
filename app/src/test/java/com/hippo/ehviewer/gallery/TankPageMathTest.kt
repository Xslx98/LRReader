package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TankPageMathTest {

    // members: 20p, 25p, 0p(broken), 10p → offsets [0, 20, 45, 45, 55]
    private val offsets = TankPageMath.pageOffsets(listOf(20, 25, 0, 10))

    @Test
    fun offsets_arePrefixSums() {
        assertEquals(listOf(0, 20, 45, 45, 55), offsets)
    }

    @Test
    fun globalPage1_firstMemberFirstPage() {
        assertEquals(1, TankPageMath.globalPage1(offsets, memberIndex = 0, page0 = 0))
    }

    @Test
    fun globalPage1_secondMemberSixthPage_is26() {
        // server doc example: 20+25 pages, page 26 == member #2 page 6
        assertEquals(26, TankPageMath.globalPage1(offsets, memberIndex = 1, page0 = 5))
    }

    @Test
    fun locate_roundTrips() {
        assertEquals(0 to 0, TankPageMath.locate(offsets, 1))
        assertEquals(1 to 5, TankPageMath.locate(offsets, 26))
        assertEquals(3 to 0, TankPageMath.locate(offsets, 46)) // 0-page member skipped
        assertEquals(3 to 9, TankPageMath.locate(offsets, 55))
    }

    @Test
    fun locate_overflow_clampsToLastPage() {
        assertEquals(3 to 9, TankPageMath.locate(offsets, 999))
    }

    @Test
    fun locate_zeroOrNegative_returnsNull() {
        assertNull(TankPageMath.locate(offsets, 0))
        assertNull(TankPageMath.locate(offsets, -3))
    }

    @Test
    fun emptyTank_locateNull() {
        val empty = TankPageMath.pageOffsets(emptyList())
        assertEquals(listOf(0), empty)
        assertNull(TankPageMath.locate(empty, 1))
    }
}
