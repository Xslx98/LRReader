package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.download.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadDbRepositoryTrackedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private lateinit var repo: DownloadDbRepository

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Suppress("DEPRECATION")
    private suspend fun row(arcid: String, state: DownloadState?) = dao.upsert(
        ArchiveLocalState(
            arcid = arcid,
            serverProfileId = 1L,
            archiveJson = """{"arcid":"$arcid","title":"T"}""",
            downloadState = state,
            downloadTime = state?.let { 1000L },
            historyTime = if (state == null) 5L else null,
        )
    )

    @Test
    fun trackedForAnyDownloadState() = runTest {
        row("wait", DownloadState.WAIT)
        row("dl", DownloadState.DOWNLOAD)
        row("failed", DownloadState.FAILED)
        row("done", DownloadState.FINISH)
        assertTrue(repo.isDownloadTracked("wait"))
        assertTrue(repo.isDownloadTracked("dl"))
        assertTrue(repo.isDownloadTracked("failed"))
        assertTrue(repo.isDownloadTracked("done"))
    }

    @Test
    fun notTrackedForHistoryOnlyOrUnknownRows() = runTest {
        row("history-only", null)
        assertFalse(repo.isDownloadTracked("history-only"))
        assertFalse(repo.isDownloadTracked("never-seen"))
    }
}
