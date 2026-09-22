package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TankMemberStrip] truncation and greying (spec 2026-09-22 §4.6). */
class TankMemberStripTest {

    @Test
    fun plan_underTheCapShowsEveryMemberWithNoTail() {
        assertEquals(TankMemberStrip.Plan(shown = 0, more = 0), TankMemberStrip.plan(0))
        assertEquals(TankMemberStrip.Plan(shown = 3, more = 0), TankMemberStrip.plan(3))
        assertEquals(TankMemberStrip.Plan(shown = 8, more = 0), TankMemberStrip.plan(8))
        assertFalse(TankMemberStrip.plan(8).hasMore)
    }

    @Test
    fun plan_overTheCapFoldsTheRestIntoOneTailCard() {
        assertEquals(TankMemberStrip.Plan(shown = 8, more = 1), TankMemberStrip.plan(9))
        assertEquals(TankMemberStrip.Plan(shown = 8, more = 32), TankMemberStrip.plan(40))
        assertTrue(TankMemberStrip.plan(9).hasMore)
    }

    @Test
    fun plan_negativeAndCustomCapsAreClamped() {
        assertEquals(TankMemberStrip.Plan(shown = 0, more = 0), TankMemberStrip.plan(-2))
        assertEquals(TankMemberStrip.Plan(shown = 2, more = 3), TankMemberStrip.plan(5, maxCovers = 2))
        assertEquals(TankMemberStrip.Plan(shown = 0, more = 5), TankMemberStrip.plan(5, maxCovers = -1))
    }

    @Test
    fun isGreyed_onlyForMissingMembersOfADownloadedTank() {
        assertTrue(TankMemberStrip.isGreyed(tankDownloaded = true, memberState = DownloadState.INVALID))
        assertTrue(TankMemberStrip.isGreyed(tankDownloaded = true, memberState = DownloadState.FAILED))
        assertFalse(TankMemberStrip.isGreyed(tankDownloaded = true, memberState = DownloadState.FINISH))
        assertFalse(TankMemberStrip.isGreyed(tankDownloaded = false, memberState = DownloadState.INVALID))
    }
}
