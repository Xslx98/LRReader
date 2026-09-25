package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The local-copy checks that route an archive to its download directory
 * instead of network streaming.
 *
 * The download worker saves pages with their source extension and the system
 * decoder reads more than JPEG/PNG/GIF/WebP, so recognition must follow the
 * shared [GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS] whitelist: otherwise a
 * fully downloaded archive (e.g. AVIF pages) is not seen as local and every
 * page is streamed again. An incomplete copy must stream the rest.
 */
class DownloadDirResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A fresh directory populated with small files of the given names. */
    private fun dirWith(vararg filenames: String): File {
        val dir = tempFolder.newFolder()
        filenames.forEach { File(dir, it).writeBytes(ByteArray(8)) }
        return dir
    }

    @Test
    fun `recognises every worker-saved decodable format, case-insensitively`() {
        for (name in listOf(
            "a.jpg", "a.jpeg", "a.png", "a.gif", "a.webp", "a.bmp",
            "0001.avif", "0001.heif", "0001.heic", "0001.PNG",
        )) {
            assertTrue(name, DownloadDirResolver.hasImageFiles(dirWith(name)))
        }
    }

    @Test
    fun `ignores non-image and undecodable files`() {
        assertFalse(DownloadDirResolver.hasImageFiles(dirWith("notes.txt")))
        // JXL has no system decoder on minSdk 28, so it must stay out of the
        // whitelist — listing it would surface undecodable pages.
        assertFalse(DownloadDirResolver.hasImageFiles(dirWith("0001.jxl")))
        assertFalse(DownloadDirResolver.hasImageFiles(tempFolder.newFolder()))
    }

    @Test
    fun `countImageFiles counts only supported image extensions`() {
        val dir = dirWith("0001.jpg", "0002.png", ".nomedia", "0003.tmp")
        assertEquals(2, DownloadDirResolver.countImageFiles(dir))
    }

    @Test
    fun `complete when file count reaches pagecount`() {
        assertTrue(DownloadDirResolver.isLocalCopyComplete(dirWith("0001.jpg", "0002.jpg", "0003.jpg"), 3))
    }

    @Test
    fun `incomplete when files are missing`() {
        assertFalse(DownloadDirResolver.isLocalCopyComplete(dirWith("0001.jpg", "0002.jpg"), 3))
    }

    @Test
    fun `unknown pagecount is treated as complete`() {
        assertTrue(DownloadDirResolver.isLocalCopyComplete(dirWith("0001.jpg"), 0))
    }
}
