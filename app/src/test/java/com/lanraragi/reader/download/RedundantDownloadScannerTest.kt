package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DownloadDbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class RedundantDownloadScannerTest {

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

    @Suppress("DEPRECATION")
    private suspend fun download(arcid: String, dirname: String) {
        db.archiveLocalStateDao().upsert(
            ArchiveLocalState(
                arcid = arcid, serverProfileId = 1L, archiveJson = "{}",
                downloadState = DownloadState.FINISH, downloadTime = 1L,
            )
        )
        repo.putDownloadDirname(arcid, dirname)
        File(root, dirname).mkdirs()
    }

    private suspend fun scanNames() = RedundantDownloadScanner(repo).scan(UniFile.fromFile(root)!!).map { it.name }

    @Test
    fun titleNamedDownloadsAreNeverRedundant() = runTest {
        // Regression: the old arcid-prefix match flagged every one of these.
        download("0123456789abcdef", "My Gallery")
        download("fedcba9876543210", "Other (2)")
        assertEquals(emptyList<String>(), scanNames())
    }

    @Test
    fun directoriesWithoutADownloadAreReported() = runTest {
        download("a1", "Kept")
        File(root, "Orphan").mkdirs()
        repo.putDownloadDirname("deleted-keep-files", "Leftover")
        File(root, "Leftover").mkdirs()
        assertEquals(listOf("Leftover", "Orphan"), scanNames())
    }

    @Test
    fun filesAndDotEntriesAreIgnored() = runTest {
        File(root, ".nomedia").writeBytes(ByteArray(0))
        File(root, "loose.jpg").writeBytes(ByteArray(10))
        File(root, ".hidden").mkdirs()
        assertEquals(emptyList<String>(), scanNames())
    }

    @Test
    fun pointerMatchIsCaseInsensitive() = runTest {
        download("a1", "Vol 1")
        File(root, "Vol 1").renameTo(File(root, "VOL 1"))
        assertEquals(emptyList<String>(), scanNames())
    }
}
