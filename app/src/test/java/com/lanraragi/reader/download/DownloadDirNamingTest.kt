package com.lanraragi.reader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDirNamingTest {

    private val arcid = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun baseName_isTheSanitisedTitleOnly() {
        assertEquals("My Gallery", DownloadDirNaming.baseName(arcid, "My Gallery"))
        assertEquals("AB", DownloadDirNaming.baseName(arcid, "A/B:*?\"<>|"))
        assertFalse(DownloadDirNaming.baseName(arcid, "Title").contains(arcid))
    }

    @Test
    fun baseName_fallsBackToArcidForEmptyTitle() {
        assertEquals(arcid, DownloadDirNaming.baseName(arcid, null))
        assertEquals(arcid, DownloadDirNaming.baseName(arcid, "   "))
        assertEquals(arcid, DownloadDirNaming.baseName(arcid, "///"))
    }

    @Test
    fun uniqueName_returnsBaseWhenFree() {
        assertEquals("Title", DownloadDirNaming.uniqueName("Title") { false })
    }

    @Test
    fun uniqueName_appendsCounterOnCollision() {
        val taken = setOf("Title", "Title (2)", "Title (3)")
        assertEquals("Title (4)", DownloadDirNaming.uniqueName("Title") { it in taken })
    }

    @Test
    fun uniqueName_keepsSuffixWithin255Bytes() {
        val base = "字".repeat(85) // 255 bytes exactly
        val name = DownloadDirNaming.uniqueName(base) { it == base }
        assertTrue(name.endsWith(" (2)"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun isLegacyName_detectsArcidPrefix() {
        assertTrue(DownloadDirNaming.isLegacyName(arcid, "$arcid-Old Title"))
        assertFalse(DownloadDirNaming.isLegacyName(arcid, "Old Title"))
        assertFalse(DownloadDirNaming.isLegacyName(arcid, "${arcid.take(8)}-x"))
    }
}
