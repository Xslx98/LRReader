package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi.Tankoubon
import com.lanraragi.reader.tankoubon.TankPickerRows.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TankPickerRowsTest {

    private fun tank(id: String, name: String, vararg members: String, summary: String? = null, tags: String? = null) =
        Tankoubon(id = id, name = name, archives = members.toList(), summary = summary, tags = tags)

    private fun List<TankPickerRows.Row>.ids() = map { it.tank.id }

    @Test
    fun `without a query recent tanks lead and the rest follow in natural name order`() {
        val tanks = listOf(
            tank("T1", "Series 10"),
            tank("T2", "Series 2"),
            tank("T3", "Alpha"),
            tank("T4", "beta"),
        )

        val rows = TankPickerRows.build(tanks, selection = emptyList(), recentIds = listOf("T4", "T1"), query = "")

        assertEquals(listOf("T4", "T1", "T3", "T2"), rows.ids())
        assertEquals(listOf(Section.RECENT, Section.RECENT, Section.ALL, Section.ALL), rows.map { it.section })
    }

    @Test
    fun `name order ignores letter case`() {
        val tanks = listOf(tank("T1", "Beta"), tank("T2", "alpha"), tank("T3", "Gamma"))

        val rows = TankPickerRows.build(tanks, selection = emptyList(), recentIds = emptyList(), query = "")

        assertEquals(listOf("T2", "T1", "T3"), rows.ids())
    }

    @Test
    fun `recent ids no longer on the server are skipped`() {
        val rows = TankPickerRows.build(
            listOf(tank("T1", "A")), selection = emptyList(), recentIds = listOf("GONE", "T1"), query = ""
        )

        assertEquals(listOf("T1"), rows.ids())
        assertEquals(Section.RECENT, rows.single().section)
    }

    @Test
    fun `contained counts how much of the selection is already a member`() {
        val tanks = listOf(tank("T1", "A", "a1", "a2", "x"), tank("T2", "B", "x"), tank("T3", "C"))

        val rows = TankPickerRows.build(tanks, selection = listOf("a1", "a2", "a3"), recentIds = emptyList(), query = "")
            .associateBy { it.tank.id }

        assertEquals(2, rows.getValue("T1").contained)
        assertEquals(0, rows.getValue("T2").contained)
        assertEquals(0, rows.getValue("T3").contained)
        assertFalse(rows.getValue("T1").containsAll)
    }

    @Test
    fun `a tank holding the whole selection is flagged`() {
        val rows = TankPickerRows.build(
            listOf(tank("T1", "A", "a1", "a2")), selection = listOf("a2", "a1"), recentIds = emptyList(), query = ""
        )

        assertTrue(rows.single().containsAll)
    }

    @Test
    fun `an empty selection is never fully contained`() {
        val rows = TankPickerRows.build(listOf(tank("T1", "A", "a1")), emptyList(), emptyList(), "")

        assertFalse(rows.single().containsAll)
    }

    @Test
    fun `pinned member tanks lead ahead of recent ones`() {
        val tanks = listOf(tank("T1", "A"), tank("T2", "B", "a1"), tank("T3", "C"), tank("T4", "D", "a1"))

        val rows = TankPickerRows.build(
            tanks, selection = listOf("a1"), recentIds = listOf("T3", "T4"), query = "", pinMembers = true
        )

        assertEquals(listOf("T2", "T4", "T3", "T1"), rows.ids())
        assertEquals(
            listOf(Section.MEMBER, Section.MEMBER, Section.RECENT, Section.ALL),
            rows.map { it.section }
        )
    }

    @Test
    fun `a query merges sections and ranks name prefix before name infix before summary or tags`() {
        val tanks = listOf(
            tank("T1", "My Hero"),
            tank("T2", "Other", summary = "hero story"),
            tank("T3", "Heroes 2"),
            tank("T4", "Unrelated"),
            tank("T5", "Plain", tags = "artist:hero"),
            tank("T6", "heroine"),
        )

        val rows = TankPickerRows.build(tanks, emptyList(), recentIds = listOf("T2"), query = "  HERO ")

        assertEquals(listOf("T3", "T6", "T1", "T2", "T5"), rows.ids())
        assertTrue(rows.all { it.section == Section.MATCH })
    }

    @Test
    fun `hasExactName ignores case and surrounding blanks`() {
        val tanks = listOf(tank("T1", "My Series"))

        assertTrue(TankPickerRows.hasExactName(tanks, " my series "))
        assertFalse(TankPickerRows.hasExactName(tanks, "my"))
        assertFalse(TankPickerRows.hasExactName(tanks, "  "))
    }
}
