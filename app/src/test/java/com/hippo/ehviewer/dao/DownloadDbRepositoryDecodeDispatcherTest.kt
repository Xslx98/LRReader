package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * Locks the dispatcher contract of [DownloadDbRepository.observeDownloads]:
 * the per-row `archive_json` decode (`toDownloadInfoView`) must run on the
 * injected decode dispatcher, NOT on the collector's dispatcher. Collectors
 * live on the main thread (`viewModelScope` in [DownloadsViewModel]), and an
 * O(N) kotlinx-serialization parse of the whole download list on main is a
 * jank source on every structural emission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadDbRepositoryDecodeDispatcherTest {

    /**
     * Inline-executing dispatcher that records whether flowOn actually
     * routed upstream work through it. isDispatchNeeded defaults to true,
     * so any flowOn(this) upstream must call [dispatch].
     */
    private class RecordingDispatcher : CoroutineDispatcher() {
        @Volatile var dispatched = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            block.run()
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private val decodeDispatcher = RecordingDispatcher()
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
        repo = DownloadDbRepository(dao, db.downloadDao(), db, decodeDispatcher)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeDownloads_decodesRowsOnInjectedDispatcher() = runTest {
        @Suppress("DEPRECATION")
        dao.upsert(
            ArchiveLocalState(
                arcid = "arc-flowon",
                serverProfileId = 1L,
                archiveJson = """{"arcid":"arc-flowon","title":"T"}""",
                downloadState = DownloadState.FINISH,
                downloadTime = 1000L,
            )
        )

        val list = repo.observeDownloads().first()

        assertEquals(1, list.size)
        assertEquals("arc-flowon", list[0].arcid)
        assertTrue(
            "archive_json decode must be routed through the injected decode dispatcher",
            decodeDispatcher.dispatched
        )
    }
}
