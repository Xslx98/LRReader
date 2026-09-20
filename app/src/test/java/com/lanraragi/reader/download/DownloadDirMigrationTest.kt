package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.ArchiveLocalStateDao
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.download.DownloadDirMigration.RowResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadDirMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private lateinit var repo: DownloadDbRepository
    private lateinit var root: File
    private lateinit var migration: DownloadDirMigration

    private val arcid = "0123456789abcdef0123456789abcdef01234567"

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.archiveLocalStateDao()
        repo = DownloadDbRepository(dao, db.downloadDao(), db, Dispatchers.Unconfined)
        root = tmp.newFolder("download")
        migration = DownloadDirMigration(repo) { UniFile.fromFile(root) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Suppress("DEPRECATION")
    private suspend fun row(id: String, title: String, state: DownloadState = DownloadState.FINISH) {
        dao.upsert(
            ArchiveLocalState(
                arcid = id,
                serverProfileId = 1L,
                archiveJson = """{"arcid":"$id","title":"$title","tags":{},"pagecount":1,"progress":0,""" +
                    """"extension":"zip","filename":"$id.zip","thumbnailUrl":"","rating":0.0,""" +
                    """"isnew":false,"lastreadtime":0,"serverProfileId":1}""",
                downloadState = state,
                downloadTime = 1000L,
            )
        )
    }

    private fun legacyDir(id: String, title: String): File =
        File(root, "$id-$title").also {
            it.mkdirs()
            File(it, "0001.jpg").writeBytes(ByteArray(2048))
        }

    @Test
    fun legacyDirIsRenamedToTitleAndPointerRepointed() = runTest {
        row(arcid, "My Gallery")
        repo.putDownloadDirname(arcid, "$arcid-My Gallery")
        legacyDir(arcid, "My Gallery")

        val outcome = migration.run()

        assertEquals(mapOf(RowResult.RENAMED to 1), outcome.results)
        assertTrue(outcome.complete)
        assertTrue(File(root, "My Gallery/0001.jpg").isFile)
        assertFalse(File(root, "$arcid-My Gallery").exists())
        assertEquals("My Gallery", repo.getDownloadDirname(arcid))
    }

    @Test
    fun collisionWithExistingSiblingGetsCounterSuffix() = runTest {
        row(arcid, "Same")
        repo.putDownloadDirname(arcid, "$arcid-Same")
        legacyDir(arcid, "Same")
        File(root, "Same").mkdirs() // another archive already owns the plain name

        assertEquals(RowResult.RENAMED, migration.migrateRow(repo.getAllDownloadInfo().single()))
        assertTrue(File(root, "Same (2)/0001.jpg").isFile)
        assertEquals("Same (2)", repo.getDownloadDirname(arcid))
    }

    @Test
    fun titleStyleNameIsLeftAlone() = runTest {
        row(arcid, "Fresh")
        repo.putDownloadDirname(arcid, "Fresh")
        File(root, "Fresh").mkdirs()

        assertEquals(RowResult.ALREADY_NEW, migration.migrateRow(repo.getAllDownloadInfo().single()))
        assertTrue(File(root, "Fresh").isDirectory)
    }

    @Test
    fun missingLegacyDirClearsThePointer() = runTest {
        row(arcid, "Gone")
        repo.putDownloadDirname(arcid, "$arcid-Gone")

        assertEquals(RowResult.POINTER_CLEARED, migration.migrateRow(repo.getAllDownloadInfo().single()))
        assertNull(repo.getDownloadDirname(arcid))
    }

    @Test
    fun activeDownloadIsSkippedAndOutcomeIncomplete() = runTest {
        row(arcid, "Busy", state = DownloadState.DOWNLOAD)
        repo.putDownloadDirname(arcid, "$arcid-Busy")
        legacyDir(arcid, "Busy")

        val outcome = migration.run()

        assertEquals(mapOf(RowResult.SKIPPED_ACTIVE to 1), outcome.results)
        assertFalse(outcome.complete)
        assertTrue(File(root, "$arcid-Busy").isDirectory)
        assertEquals("$arcid-Busy", repo.getDownloadDirname(arcid))
    }

    @Test
    fun rowWithoutPointerIsNoOp() = runTest {
        row(arcid, "NoPtr")
        assertEquals(RowResult.NO_POINTER, migration.migrateRow(repo.getAllDownloadInfo().single()))
    }
}
