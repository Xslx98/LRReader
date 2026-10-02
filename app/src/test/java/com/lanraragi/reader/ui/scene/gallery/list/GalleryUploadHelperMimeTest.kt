package com.lanraragi.reader.ui.scene.gallery.list

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload picker's `EXTRA_MIME_TYPES` whitelist decides which files the
 * SAF picker lets the user tap. LANraragi (dev after 0.9.81) accepts `.cbw`
 * ComicBookWeb files, which are XML — they were greyed out because only
 * archive MIME types were listed.
 */
class GalleryUploadHelperMimeTest {

    private val mimes = GalleryUploadHelper.UPLOAD_MIME_TYPES.toSet()

    @Test
    fun whitelist_keepsArchiveTypes() {
        assertTrue(mimes.containsAll(setOf("application/zip", "application/x-rar-compressed", "application/x-7z-compressed")))
    }

    @Test
    fun whitelist_acceptsCbwXml() {
        // Android reports .cbw as either application/xml or text/xml depending
        // on the provider; both must be present or the file stays unselectable.
        assertTrue(mimes.contains("application/xml"))
        assertTrue(mimes.contains("text/xml"))
    }
}
