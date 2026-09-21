package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.ui.scene.gallery.list.TankMergePlanner.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matrix from spec 2026-09-22 §9 for the pure merge planner. */
class TankMergePlannerTest {

    private val tank = "TANK_1688000000"

    private fun ids(n: Int, tankAt: Int = -1): List<String> =
        List(n) { i -> if (i == tankAt) tank else "a$i" }

    @Test
    fun `all succeeded rows on screen fly and are removed descending, tank row on screen is the destination`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(10, tankAt = 7),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a1", "a3", "a4"),
            tankId = tank, groupMode = true,
        )
        assertEquals(listOf(1, 3, 4), plan.flyerPositions)
        assertEquals(0, plan.extraCount)
        assertEquals(listOf(4, 3, 1), plan.removals)
        assertNull(plan.provisionalInsertAt)
        assertEquals(Destination.Row(7, needsScroll = false), plan.destination)
    }

    @Test
    fun `succeeded rows off screen do not fly but are still removed and counted in the pill`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(30, tankAt = 2),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a5", "a15", "a25"),
            tankId = tank, groupMode = true,
        )
        assertEquals(listOf(5), plan.flyerPositions)
        assertEquals(2, plan.extraCount)
        assertEquals(listOf(25, 15, 5), plan.removals)
    }

    @Test
    fun `more than eight visible rows cap the flyers at eight and fold the rest into the pill`() {
        val succeeded = (0 until 12).map { "a$it" }
        val plan = TankMergePlanner.plan(
            loadedIds = ids(20, tankAt = 15),
            firstVisible = 0, lastVisible = 14,
            succeeded = succeeded,
            tankId = tank, groupMode = true,
        )
        assertEquals((0 until 8).toList(), plan.flyerPositions)
        assertEquals(4, plan.extraCount)
        assertEquals((11 downTo 0).toList(), plan.removals)
    }

    @Test
    fun `failed rows are neither flown nor removed`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(6, tankAt = 5),
            firstVisible = 0, lastVisible = 5,
            succeeded = listOf("a0", "a2"), // a1 failed → not in succeeded
            tankId = tank, groupMode = true,
        )
        assertEquals(listOf(0, 2), plan.flyerPositions)
        assertEquals(listOf(2, 0), plan.removals)
    }

    @Test
    fun `tank row absent in group mode inserts a provisional row at the topmost member index`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(10),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a6", "a3", "a8"),
            tankId = tank, groupMode = true,
        )
        assertEquals(3, plan.provisionalInsertAt)
        assertEquals(Destination.ProvisionalRow, plan.destination)
        assertEquals(listOf(8, 6, 3), plan.removals)
    }

    @Test
    fun `tank row loaded but off screen within three screens scrolls to it`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(40, tankAt = 30),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a1"),
            tankId = tank, groupMode = true,
        )
        assertEquals(Destination.Row(30, needsScroll = true), plan.destination)
        assertNull(plan.provisionalInsertAt)
    }

    @Test
    fun `tank row loaded but further than three screens away falls back to the batch button`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(60, tankAt = 59),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a1"),
            tankId = tank, groupMode = true,
        )
        assertEquals(Destination.BatchButton, plan.destination)
        assertNull("a loaded tank row must never be duplicated by a provisional one", plan.provisionalInsertAt)
        assertEquals(listOf(1), plan.removals)
    }

    @Test
    fun `tank row above the window counts distance from the first visible row`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(60, tankAt = 5),
            firstVisible = 20, lastVisible = 29,
            succeeded = listOf("a21"),
            tankId = tank, groupMode = true,
        )
        assertEquals(Destination.Row(5, needsScroll = true), plan.destination)
    }

    @Test
    fun `group mode off keeps every row, flies visible covers to the batch button`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(10),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a1", "a2"),
            tankId = tank, groupMode = false,
        )
        assertEquals(listOf(1, 2), plan.flyerPositions)
        assertTrue(plan.removals.isEmpty())
        assertNull(plan.provisionalInsertAt)
        assertEquals(Destination.BatchButton, plan.destination)
    }

    @Test
    fun `group mode off still lands on a tank row if one happens to be on screen`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(10, tankAt = 0),
            firstVisible = 0, lastVisible = 9,
            succeeded = listOf("a1"),
            tankId = tank, groupMode = false,
        )
        assertEquals(Destination.Row(0, needsScroll = false), plan.destination)
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `succeeded archive not loaded in this list only rides the pill`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(5),
            firstVisible = 0, lastVisible = 4,
            succeeded = listOf("zzz"),
            tankId = tank, groupMode = true,
        )
        assertTrue(plan.flyerPositions.isEmpty())
        assertEquals(1, plan.extraCount)
        assertTrue(plan.removals.isEmpty())
        assertNull(plan.provisionalInsertAt)
        assertEquals(Destination.BatchButton, plan.destination)
    }

    @Test
    fun `no visible window means no flyers, everything rides the pill`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(5, tankAt = 4),
            firstVisible = -1, lastVisible = -1,
            succeeded = listOf("a0", "a1"),
            tankId = tank, groupMode = true,
        )
        assertTrue(plan.flyerPositions.isEmpty())
        assertEquals(2, plan.extraCount)
        assertEquals(Destination.Row(4, needsScroll = false), plan.destination)
    }

    @Test
    fun `empty success set is a no-op plan`() {
        val plan = TankMergePlanner.plan(
            loadedIds = ids(5),
            firstVisible = 0, lastVisible = 4,
            succeeded = emptyList(),
            tankId = tank, groupMode = true,
        )
        assertTrue(plan.isNoOp)
    }
}
