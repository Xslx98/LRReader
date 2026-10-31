package com.lanraragi.reader.storage

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.reader.spider.SpiderDen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [StorageFile] replaced framework.unifile (2.4 P3). The stored formats must
 * not change: v2.3.0 rows hold `DOWNLOAD_ROOT_URI` = `Uri.fromFile(root)` and
 * resolve their pages through it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class StorageFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("download")
    }

    @Test
    fun uriIsTheFileUriStoredByEarlierVersions() {
        val dir = File(root, "My Title [x]")
        assertEquals(Uri.fromFile(dir).toString(), StorageFile(dir).uri.toString())
    }

    @Test
    fun fromUriResolvesAStoredFileUri() {
        val dir = File(root, "My Title [x]").apply { mkdirs() }

        val resolved = StorageFile.fromUri(Uri.fromFile(dir))

        assertEquals(dir.path, resolved?.file?.path)
        assertTrue(resolved!!.isDirectory)
    }

    @Test
    fun fromUriDoesNotResolveALegacyContentRoot() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ALRReader"
        assertNull(StorageFile.fromUri(Uri.parse(tree)))
        assertNull(StorageFile.fromUri(null))
    }

    @Test
    fun legacyContentRootFallsBackToTheCurrentDownloadRoot() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        DownloadSettings.putDownloadLocation(StorageFile(root))

        val resolved = SpiderDen.resolveRootDir("content://com.android.externalstorage.documents/tree/primary%3ALRReader")

        assertEquals(StorageFile(root).uri, resolved?.uri)
    }

    @Test
    fun deleteRemovesADirectoryWithEverythingUnderIt() {
        val dir = File(root, "title").apply { mkdirs() }
        File(dir, "1.jpg").writeText("x")
        File(dir, "sub").mkdirs()
        File(dir, "sub/2.jpg").writeText("y")

        assertTrue(StorageFile(dir).delete())
        assertFalse(dir.exists())
    }

    @Test
    fun listFilesFiltersByNameAndIsNullForAFile() {
        File(root, "1.jpg").writeText("x")
        File(root, "note.txt").writeText("y")

        val images = StorageFile(root).listFiles { it.endsWith(".jpg") }!!.map { it.name }

        assertEquals(listOf("1.jpg"), images)
        assertNull(StorageFile(File(root, "1.jpg")).listFiles())
    }

    @Test
    fun findFileReturnsOnlyExistingChildrenWhileSubFileAlwaysDoes() {
        val dir = StorageFile(root)

        assertNull(dir.findFile("missing"))
        assertFalse(dir.subFile("missing").exists())
        File(root, "present").writeText("x")
        assertEquals("present", dir.findFile("present")?.name)
    }

    @Test
    fun createFileKeepsAnExistingFileAndRefusesADirectory() {
        File(root, "page.jpg").writeText("keep")
        File(root, "taken").mkdirs()
        val dir = StorageFile(root)

        val existing = dir.createFile("page.jpg")
        val fresh = dir.createFile("new.jpg")

        assertEquals("keep", existing!!.file.readText())
        assertEquals(0L, fresh!!.length())
        assertNull(dir.createFile("taken"))
    }

    @Test
    fun createDirectoryAndEnsureDirCreateMissingParents() {
        assertTrue(StorageFile(File(root, "a/b")).ensureDir())
        assertTrue(StorageFile(root).createDirectory("c/d")!!.isDirectory)
    }

    @Test
    fun renameToMovesWithinTheDirectoryAndRepointsTheObject() {
        val marker = StorageFile(root).createFile(".lrr.json.tmp")!!
        marker.openOutputStream().use { it.write(byteArrayOf(1, 2)) }

        assertTrue(marker.renameTo(".lrr.json"))

        assertEquals(".lrr.json", marker.name)
        assertArrayEquals(byteArrayOf(1, 2), marker.openInputStream().use { it.readBytes() })
        assertFalse(File(root, ".lrr.json.tmp").exists())
    }
}
