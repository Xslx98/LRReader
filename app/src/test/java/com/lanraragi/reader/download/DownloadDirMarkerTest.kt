package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.mapper.toArchiveJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Audit 2026-10-04 C05 (first slice): every download directory carries a
 * `.lrr.json` naming its arcid, existing directories get one at boot, and
 * the redundancy scan never offers a directory whose marker names a
 * tracked download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadDirMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadDbRepository
    private lateinit var root: File

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined)
        root = tmp.newFolder("download")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dir(name: String) = UniFile.fromFile(File(root, name).apply { mkdirs() })!!

    @Suppress("DEPRECATION")
    private suspend fun download(arcid: String, dirname: String?, title: String = "T") {
        db.archiveLocalStateDao().upsert(
            ArchiveLocalState(
                arcid = arcid, serverProfileId = 7L,
                archiveJson = Archive(
                    arcid = arcid, title = title, tags = emptyMap(), pagecount = 12, progress = 0,
                    extension = "zip", filename = "$arcid.zip", thumbnailUrl = "", rating = 0f,
                    isnew = false, lastreadtime = 0L, summary = null, serverProfileId = 7L,
                ).toArchiveJson(),
                downloadState = DownloadState.FINISH, downloadTime = 1L,
            )
        )
        if (dirname != null) repo.putDownloadDirname(arcid, dirname)
    }

    @Test
    fun write_thenRead_roundTripsAndLeavesNoTempFile() {
        val d = dir("Vol 1")
        val marker = DownloadDirMarker(arcid = "a1", serverProfileId = 3L, title = "Vol 1", pagecount = 20)

        assertTrue(DownloadDirMarker.write(d, marker))

        assertEquals(marker, DownloadDirMarker.read(d))
        assertEquals(listOf(DownloadDirMarker.FILE_NAME), File(root, "Vol 1").list()!!.toList())
    }

    @Test
    fun write_replacesAnOutdatedMarker() {
        val d = dir("Vol 1")
        DownloadDirMarker.write(d, DownloadDirMarker(arcid = "a1", serverProfileId = 3L))
        val updated = DownloadDirMarker(arcid = "a1", serverProfileId = 3L, title = "Vol 1", pagecount = 20)

        assertTrue(DownloadDirMarker.write(d, updated))

        assertEquals(updated, DownloadDirMarker.read(d))
    }

    @Test
    fun read_garbageOrMissing_isNull() {
        val d = dir("Vol 1")
        assertNull(DownloadDirMarker.read(d))
        File(root, "Vol 1/${DownloadDirMarker.FILE_NAME}").writeText("{not json")
        assertNull(DownloadDirMarker.read(d))
    }

    @Test
    fun backfill_writesMarkerIntoEveryExistingDownloadDir() = runTest {
        download("a1", "Vol 1", title = "Vol 1")
        dir("Vol 1")
        download("a2", null) // not started yet: no directory, nothing to do

        val complete = DownloadDirMarkerBackfill(repo) { arcid, _ ->
            repo.getDownloadDirname(arcid)?.let { UniFile.fromFile(File(root, it)) }
        }.run()

        assertTrue(complete)
        assertEquals(
            DownloadDirMarker(arcid = "a1", serverProfileId = 7L, title = "Vol 1", pagecount = 12),
            DownloadDirMarker.read(dir("Vol 1"))
        )
        assertFalse(File(root, "a2").exists())
    }

    @Test
    fun scanner_keepsDirectoryWhoseMarkerNamesATrackedDownload() = runTest {
        // The pointer names another directory (e.g. renamed by hand); the
        // marker still ties this one to a tracked download.
        download("a1", "Vol 1")
        dir("Vol 1")
        DownloadDirMarker.write(dir("Vol 1 copy"), DownloadDirMarker(arcid = "a1", serverProfileId = 7L))
        DownloadDirMarker.write(dir("Gone"), DownloadDirMarker(arcid = "deleted", serverProfileId = 7L))

        val names = RedundantDownloadScanner(repo).scan(UniFile.fromFile(root)!!).map { it.name }

        assertEquals(listOf("Gone"), names)
    }
}
