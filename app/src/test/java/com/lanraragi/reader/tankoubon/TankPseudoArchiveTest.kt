package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TankPseudoArchiveTest {

    private val memberCover = "http://lrr.local/api/archives/${"a".repeat(40)}/thumbnail"

    @Test
    fun `a previously-empty tank shows the first added member's cover`() {
        val row = TankPseudoArchive.provisional(
            tankId = "TANK_1688000000",
            tankName = "Vol. 1",
            wasEmpty = true,
            firstAddedThumbnailUrl = memberCover,
            sourceProfileId = 7L,
            sourceBaseUrl = "http://lrr.local",
        )

        assertEquals("TANK_1688000000", row.arcid)
        assertEquals("Vol. 1", row.title)
        assertEquals(memberCover, row.thumbnailUrl)
        assertEquals(7L, row.serverProfileId)
        assertEquals("", row.extension)
        assertEquals("", row.filename)
        assertEquals(0, row.pagecount)
        assertFalse(row.isnew)
    }

    @Test
    fun `a tank that already had members keeps the tank thumbnail route`() {
        val row = TankPseudoArchive.provisional(
            tankId = "TANK_1688000000",
            tankName = "Vol. 1",
            wasEmpty = false,
            firstAddedThumbnailUrl = memberCover,
            sourceProfileId = 7L,
            sourceBaseUrl = "http://lrr.local",
        )

        assertEquals("http://lrr.local/api/tankoubons/TANK_1688000000/thumbnail", row.thumbnailUrl)
    }

    @Test
    fun `an empty tank whose first member is not loaded falls back to the tank thumbnail route`() {
        val row = TankPseudoArchive.provisional(
            tankId = "TANK_1688000000",
            tankName = "Vol. 1",
            wasEmpty = true,
            firstAddedThumbnailUrl = null,
            sourceProfileId = 7L,
            sourceBaseUrl = "http://lrr.local",
        )

        assertEquals("http://lrr.local/api/tankoubons/TANK_1688000000/thumbnail", row.thumbnailUrl)
    }
}
