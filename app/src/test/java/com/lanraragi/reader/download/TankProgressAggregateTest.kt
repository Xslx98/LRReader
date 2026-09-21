package com.lanraragi.reader.download

import com.lanraragi.reader.dao.DownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Aggregate progress behind a tank card (spec 2026-09-21 §4): sums over members with a live snapshot. */
class TankProgressAggregateTest {

    private fun info(arcid: String, state: DownloadState = DownloadState.NONE, pages: Int = 0) =
        DownloadInfo().also { it.arcid = arcid; it.state = state; it.pagecount = pages }

    @Test
    fun `finished members count as fully done and queued members count toward the total`() {
        val snaps = mapOf("active" to ProgressSnapshot("active", speed = 10L, finished = 3, total = 10))
        val agg = TankProgressAggregate.of(
            "TANK_1",
            listOf(
                info("done", DownloadState.FINISH, pages = 12),
                info("active", DownloadState.DOWNLOAD, pages = 10),
                info("queued", DownloadState.WAIT, pages = 8),
                info("unknown", DownloadState.WAIT, pages = 0),
            ),
        ) { snaps[it] }!!

        assertEquals(15, agg.finished)
        assertEquals(30, agg.total)
        assertEquals(10L, agg.speed)
    }

    @Test
    fun `a finished member without a known page count contributes nothing`() {
        val agg = TankProgressAggregate.of("TANK_1", listOf(info("done", DownloadState.FINISH), info("q", DownloadState.WAIT, 5))) { null }!!
        assertEquals(0, agg.finished)
        assertEquals(5, agg.total)
    }

    @Test
    fun `sums finished total speed and partial pages over members with snapshots`() {
        val snaps = mapOf(
            "a" to ProgressSnapshot("a", speed = 100L, finished = 2, total = 5, partialPages = 0.5f),
            "b" to ProgressSnapshot("b", speed = 50L, finished = 4, total = 4),
            "c" to ProgressSnapshot("c", speed = -1L, finished = 0, total = -1),
        )
        val agg = TankProgressAggregate.of("TANK_1", listOf(info("a"), info("b"), info("c"), info("d"))) { snaps[it] }!!

        assertEquals("TANK_1", agg.arcid)
        assertEquals(6, agg.finished)
        assertEquals(9, agg.total)
        assertEquals(150L, agg.speed)
        assertEquals(0.5f, agg.partialPages, 0f)
    }

    @Test
    fun `no member snapshot yields null`() {
        assertNull(TankProgressAggregate.of("TANK_1", listOf(info("a"))) { null })
        assertNull(TankProgressAggregate.of("TANK_1", emptyList()) { null })
    }
}
