package com.lanraragi.reader.ui.scene.download

import com.lanraragi.reader.dao.DownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-select expansion (spec 2026-09-21 §4): a selected tank card stands
 * for its member rows in batch start / stop / delete, while label moves
 * leave cards out.
 */
class DownloadBatchSelectionTest {

    private fun info(arcid: String) = DownloadInfo().also { it.arcid = arcid }

    private val members = mapOf(
        "TANK_1" to listOf(info("m1"), info("m2")),
        "TANK_2" to listOf(info("m3")),
    )

    @Test
    fun `cards expand into members in selection order, rows pass through`() {
        val expanded = DownloadBatchSelection.expand(
            listOf(info("solo"), info("TANK_1"), info("TANK_2")),
        ) { members[it].orEmpty() }

        assertEquals(listOf("solo", "m1", "m2", "m3"), expanded.infos.map { it.arcid })
        assertEquals(listOf("solo", "m1", "m2", "m3"), expanded.arcids)
        assertEquals(listOf("TANK_1", "TANK_2"), expanded.cardIds)
        assertEquals(listOf("solo"), expanded.rowsOnly.map { it.arcid })
    }

    @Test
    fun `a card with no members contributes nothing but is still listed`() {
        val expanded = DownloadBatchSelection.expand(listOf(info("TANK_9"))) { emptyList() }
        assertTrue(expanded.infos.isEmpty())
        assertEquals(listOf("TANK_9"), expanded.cardIds)
    }

    @Test
    fun `plain selection has no cards`() {
        val expanded = DownloadBatchSelection.expand(listOf(info("a"), info("b"))) { emptyList() }
        assertTrue(expanded.cardIds.isEmpty())
        assertFalse(expanded.hasCards)
        assertEquals(2, expanded.rowsOnly.size)
    }
}
