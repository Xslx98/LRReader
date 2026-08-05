package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the projection contract of [ArchiveLocalStateDao.observeAllDownloads]
 * (audit #39): the observed rows must NOT carry history/favorite columns.
 *
 * ARCHIVE_LOCAL_STATE is shared with history/favorites, and Room invalidation
 * is table-granular — the downloads observer requeries on every history write.
 * The downstream `distinctUntilChanged` is the only thing standing between
 * those requeries and an O(N) archive_json decode, and it only works if rows
 * compare EQUAL when nothing download-relevant changed. With the old
 * `SELECT *`, a per-page scroll-fraction save (vertical reading mode writes
 * one per page boundary) changed the row value and re-ran the whole decode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadObserverProjectionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.archiveLocalStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun scrollFractionWrite_doesNotChangeObservedDownloadRows() = runTest {
        @Suppress("DEPRECATION")
        dao.upsert(
            ArchiveLocalState(
                arcid = "arc-proj",
                serverProfileId = 1L,
                archiveJson = """{"arcid":"arc-proj","title":"T"}""",
                downloadState = DownloadState.FINISH,
                downloadTime = 1000L,
                historyTime = 500L,
                historyMode = 1,
            )
        )

        val before = dao.observeAllDownloads().first()
        dao.updateHistoryScrollFractionForProfile("arc-proj", 1L, 0.42f)
        val after = dao.observeAllDownloads().first()

        assertEquals(
            "history-only writes must not change the observed download rows " +
                "(distinctUntilChanged depends on value equality)",
            before,
            after,
        )
    }

    @Test
    fun downloadStateWrite_changesObservedDownloadRows() = runTest {
        @Suppress("DEPRECATION")
        dao.upsert(
            ArchiveLocalState(
                arcid = "arc-proj2",
                serverProfileId = 1L,
                archiveJson = """{"arcid":"arc-proj2","title":"T"}""",
                downloadState = DownloadState.NONE,
                downloadTime = 1000L,
            )
        )

        val before = dao.observeAllDownloads().first()
        dao.updateDownloadTime("arc-proj2", 2000L)
        val after = dao.observeAllDownloads().first()

        org.junit.Assert.assertNotEquals(
            "download-column writes must still flow through",
            before,
            after,
        )
    }
}
