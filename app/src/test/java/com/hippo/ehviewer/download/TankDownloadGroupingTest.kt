package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.TankDownloadGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure grouping contract for the downloads list's tank cards (Track 2). */
class TankDownloadGroupingTest {

    private fun info(
        arcid: String,
        tankId: String? = null,
        state: DownloadState = DownloadState.FINISH,
        time: Long = 0L,
        thumb: String? = "http://t/$arcid",
    ) = DownloadInfo().also {
        it.arcid = arcid
        it.tankId = tankId
        it.state = state
        it.time = time
        it.thumb = thumb
        it.title = arcid.take(4)
    }

    private fun group(tankId: String = TANK, name: String = "Tank", created: Long = 0L) =
        TankDownloadGroup(tankId, 1L, name, "[]", created)

    @Test
    fun `tagged members fold into one card with aggregate fields`() {
        val result = TankDownloadGrouping.group(
            listOf(
                info("solo", time = 50L),
                info("m1", TANK, DownloadState.FINISH, time = 10L),
                info("m2", TANK, DownloadState.FINISH, time = 30L),
            ),
            listOf(group(name = "MyTank")),
        )

        assertEquals(listOf("solo", TANK), result.display.map { it.arcid })
        val card = result.display.single { it.arcid == TANK }
        assertEquals("MyTank", card.title)
        assertEquals(DownloadState.FINISH, card.state)
        assertEquals(30L, card.time)
        assertEquals(TANK, card.tankId)
        assertEquals(listOf("m1", "m2"), result.tankMembers.getValue(TANK).map { it.arcid })
    }

    @Test
    fun `any active member makes the card DOWNLOAD`() {
        val result = TankDownloadGrouping.group(
            listOf(
                info("m1", TANK, DownloadState.FINISH),
                info("m2", TANK, DownloadState.DOWNLOAD),
            ),
            listOf(group()),
        )
        assertEquals(DownloadState.DOWNLOAD, result.display.single().state)
    }

    @Test
    fun `failed member without active ones makes the card FAILED`() {
        val result = TankDownloadGrouping.group(
            listOf(
                info("m1", TANK, DownloadState.FINISH),
                info("m2", TANK, DownloadState.FAILED),
            ),
            listOf(group()),
        )
        assertEquals(DownloadState.FAILED, result.display.single().state)
    }

    @Test
    fun `tag without a live group row stays standalone`() {
        val result = TankDownloadGrouping.group(
            listOf(info("m1", "TANK_0000000404")),
            listOf(group()),
        )
        assertEquals("m1", result.display.single().arcid)
        assertTrue(result.tankMembers.isEmpty())
    }

    @Test
    fun `group with no surviving member rows renders no card`() {
        val result = TankDownloadGrouping.group(
            listOf(info("solo")),
            listOf(group()),
        )
        assertEquals(listOf("solo"), result.display.map { it.arcid })
    }

    @Test
    fun `card sorts by newest member activity and keeps desc order`() {
        val result = TankDownloadGrouping.group(
            listOf(
                info("old", time = 5L),
                info("newest", time = 100L),
                info("m1", TANK, time = 60L),
            ),
            listOf(group()),
        )
        assertEquals(listOf("newest", TANK, "old"), result.display.map { it.arcid })
    }

    @Test
    fun `card thumb falls back across members`() {
        val result = TankDownloadGrouping.group(
            listOf(
                info("m1", TANK, thumb = null),
                info("m2", TANK, thumb = "http://t/m2"),
            ),
            listOf(group()),
        )
        assertEquals("http://t/m2", result.display.single().thumb)
    }

    @Test
    fun `no groups passes the list through untouched`() {
        val all = listOf(info("a"), info("b", tankId = TANK))
        val result = TankDownloadGrouping.group(all, emptyList())
        assertEquals(all, result.display)
        assertNull(result.display[0].tankId)
    }

    private companion object {
        const val TANK = "TANK_1688000000"
    }
}
