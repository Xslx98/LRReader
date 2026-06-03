package com.hippo.ehviewer.ui

import com.hippo.ehviewer.gallery.GalleryProvider2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the local-file recognition whitelist that routing relies on
 * ([GalleryOpenHelper.getLocalDownloadDir] → [GalleryOpenHelper.hasImageFiles]).
 *
 * The download worker saves pages with their source extension and the system
 * decoder reads more than the classic JPEG/PNG/GIF/WebP set, so the whitelist
 * must match — otherwise a fully-downloaded archive (e.g. AVIF pages) is not
 * recognised as local and gets misrouted to network streaming (re-downloading
 * every page). [hasImageFiles] must therefore delegate to the single source of
 * truth, [GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS].
 */
class GalleryOpenHelperImageFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A fresh directory populated with empty files of the given names. */
    private fun dirWith(vararg filenames: String): File {
        val dir = tempFolder.newFolder()
        filenames.forEach { File(dir, it).writeBytes(byteArrayOf(0)) }
        return dir
    }

    @Test
    fun `recognises an avif-only download directory`() {
        assertTrue(GalleryOpenHelper.hasImageFiles(dirWith("0001.avif")))
    }

    @Test
    fun `recognises heif pages`() {
        assertTrue(GalleryOpenHelper.hasImageFiles(dirWith("0001.heif")))
    }

    @Test
    fun `recognises heic pages`() {
        assertTrue(GalleryOpenHelper.hasImageFiles(dirWith("0001.heic")))
    }

    @Test
    fun `still recognises legacy raster formats after delegating to the shared whitelist`() {
        for (name in listOf("a.jpg", "a.jpeg", "a.png", "a.gif", "a.webp", "a.bmp")) {
            assertTrue(name, GalleryOpenHelper.hasImageFiles(dirWith(name)))
        }
    }

    @Test
    fun `matches extensions case-insensitively`() {
        assertTrue(GalleryOpenHelper.hasImageFiles(dirWith("0001.PNG")))
    }

    @Test
    fun `ignores non-image and undecodable files`() {
        assertFalse(GalleryOpenHelper.hasImageFiles(dirWith("notes.txt")))
        // JXL has no system decoder on minSdk 28, so it must stay out of the
        // whitelist — listing it would surface undecodable pages.
        assertFalse(GalleryOpenHelper.hasImageFiles(dirWith("0001.jxl")))
    }

    @Test
    fun `empty directory has no image files`() {
        assertFalse(GalleryOpenHelper.hasImageFiles(tempFolder.newFolder()))
    }

    @Test
    fun `shared whitelist covers worker-saved decodable formats and excludes jxl`() {
        val exts = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.toList()
        assertTrue(
            "whitelist missing a worker-saved/decodable format: $exts",
            exts.containsAll(
                listOf(
                    ".jpg", ".jpeg", ".png", ".gif", ".webp",
                    ".bmp", ".avif", ".heic", ".heif",
                )
            )
        )
        assertFalse("jxl is undecodable by the system decoder", ".jxl" in exts)
    }
}
