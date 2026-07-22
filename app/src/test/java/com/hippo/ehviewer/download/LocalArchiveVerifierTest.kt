package com.hippo.ehviewer.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Locks the offline completeness contract used when the page-list fetch
 * fails: every page 1..pagecount must have a valid non-tmp candidate file
 * on disk, otherwise the worker falls through to the normal failure path.
 */
class LocalArchiveVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** JPEG magic + padding, comfortably above MIN_IMAGE_SIZE. */
    private fun jpegBytes(size: Int = 2048): ByteArray =
        ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xE0.toByte()
        }

    private fun page(dir: File, name: String, bytes: ByteArray = jpegBytes()) {
        File(dir, name).writeBytes(bytes)
    }

    @Test
    fun `complete dir with real image magic passes with default validator`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg")
        page(dir, "0002.png", jpegBytes()) // extension is irrelevant, magic decides
        page(dir, "0003.webp")
        File(dir, ".nomedia").writeBytes(ByteArray(0))
        assertTrue(LocalArchiveVerifier.isComplete(dir, pagecount = 3))
    }

    @Test
    fun `missing middle page fails`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg")
        page(dir, "0003.jpg")
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 3, validate = { true }))
    }

    @Test
    fun `unknown or non-positive pagecount fails`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg")
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 0, validate = { true }))
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = -1, validate = { true }))
    }

    @Test
    fun `null or non-directory fails`() {
        assertFalse(LocalArchiveVerifier.isComplete(null, pagecount = 1, validate = { true }))
        val file = tmp.newFile("not-a-dir")
        assertFalse(LocalArchiveVerifier.isComplete(file, pagecount = 1, validate = { true }))
    }

    @Test
    fun `undersized file fails even when validator accepts`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg", jpegBytes(size = 16))
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 1, validate = { true }))
    }

    @Test
    fun `tmp leftovers do not count as pages`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg")
        page(dir, "0002.jpg.3f9a.tmp")
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 2, validate = { true }))
        page(dir, "0002.jpg")
        assertTrue(LocalArchiveVerifier.isComplete(dir, pagecount = 2, validate = { true }))
    }

    @Test
    fun `rejecting validator fails`() {
        val dir = tmp.newFolder()
        page(dir, "0001.jpg")
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 1, validate = { false }))
    }

    @Test
    fun `digit prefix must match the page number exactly`() {
        val dir = tmp.newFolder()
        page(dir, "00010.jpg") // page 10, not page 1
        assertFalse(LocalArchiveVerifier.isComplete(dir, pagecount = 1, validate = { true }))
    }
}
