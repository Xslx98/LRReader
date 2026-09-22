package com.lanraragi.reader.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DownloadPageNamingTest {

    private val dir = File("dl")

    @Test
    fun pageFile_isOneBasedZeroPaddedWithServerExtension() {
        assertEquals(File(dir, "0001.png"), DownloadPageNaming.pageFile(dir, 0, "arc/000.png"))
        assertEquals(File(dir, "0042.webp"), DownloadPageNaming.pageFile(dir, 41, "x/y/041.webp"))
        assertEquals(File(dir, "1000.jpg"), DownloadPageNaming.pageFile(dir, 999, "p.jpg"))
    }

    @Test
    fun pageFile_usesAsciiDigitsUnderAnyDefaultLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("0012.png", DownloadPageNaming.pageFile(java.io.File("d"), 11, "p/12.png").name)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun pageFile_defaultsToJpgWhenPathHasNoExtension() {
        assertEquals(File(dir, "0003.jpg"), DownloadPageNaming.pageFile(dir, 2, "noext"))
    }

    @Test
    fun extensionOf_usesLastDotOnly() {
        assertEquals(".jpg", DownloadPageNaming.extensionOf("a.b/c.d.jpg"))
        assertEquals(".jpg", DownloadPageNaming.extensionOf("plain"))
    }
}
