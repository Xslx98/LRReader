package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadState
import com.lanraragi.reader.mapper.toArchiveJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A history write must not wipe what the shared row's `archive_json`
 * already knows: history callers pass lossy views (pagecount 0), and a
 * download row whose pagecount drops to 0 is treated as complete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class HistoryRepositoryMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private lateinit var repo: HistoryRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.archiveLocalStateDao()
        repo = HistoryRepository(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun archive(pagecount: Int, progress: Int, summary: String?, tags: Map<String, List<String>>) = Archive(
        arcid = "a1", title = "T", tags = tags, pagecount = pagecount, progress = progress,
        extension = "zip", filename = "a1.zip", thumbnailUrl = "", rating = 0f, isnew = false,
        lastreadtime = 0L, summary = summary, serverProfileId = 1L,
    )

    @Suppress("DEPRECATION")
    @Test
    fun lossyHistoryWriteKeepsTheDownloadRowsPagecountProgressAndSummary() = runTest {
        val full = archive(pagecount = 50, progress = 12, summary = "S", tags = mapOf("artist" to listOf("x")))
        dao.upsert(
            ArchiveLocalState(
                arcid = "a1", serverProfileId = 1L, archiveJson = full.toArchiveJson(),
                downloadState = DownloadState.DOWNLOAD, downloadTime = 1L,
            )
        )

        repo.putHistoryInfo(archive(pagecount = 0, progress = 0, summary = null, tags = emptyMap()))

        val stored = repo.getArchiveSnapshot("a1", 1L)!!
        assertEquals(50, stored.pagecount)
        assertEquals(12, stored.progress)
        assertEquals("S", stored.summary)
        assertEquals(mapOf("artist" to listOf("x")), stored.tags)
        assertEquals(DownloadState.DOWNLOAD, dao.loadByArcidAndProfile("a1", 1L)!!.downloadState)
    }

    @Test
    fun knownIncomingValuesWin() {
        val merged = HistoryRepository.mergeSnapshot(
            existing = archive(pagecount = 50, progress = 12, summary = "old", tags = emptyMap()),
            incoming = archive(pagecount = 60, progress = 30, summary = "new", tags = mapOf("a" to listOf("b"))),
        )
        assertEquals(60, merged.pagecount)
        assertEquals(30, merged.progress)
        assertEquals("new", merged.summary)
    }

    @Test
    fun recordSessionProgress_movesTheSnapshotToWhereTheSessionEnded() = runTest {
        repo.putHistoryInfo(archive(pagecount = 100, progress = 10, summary = null, tags = emptyMap()))
        repo.recordSessionProgress("a1", 1L, 80)
        val stored = repo.getArchiveSnapshot("a1", 1L)!!
        assertEquals(80, stored.progress)
        assertEquals(100, stored.pagecount)
    }

    @Test
    fun clearHistory_clearsOnlyTheActiveProfile() = runTest {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("history_clear_test", Context.MODE_PRIVATE)
        com.lanraragi.reader.client.api.LRRAuthManager.initializeForTesting(prefs)
        try {
            com.lanraragi.reader.client.api.LRRAuthManager.setActiveProfileId(1L)
            repo.putHistoryInfo(archive(pagecount = 5, progress = 1, summary = null, tags = emptyMap()))
            repo.putHistoryInfo(
                archive(pagecount = 5, progress = 1, summary = null, tags = emptyMap()).copy(serverProfileId = 2L)
            )

            repo.clearHistory()

            assertEquals(null, dao.loadByArcidAndProfile("a1", 1L))
            assertEquals(true, dao.loadByArcidAndProfile("a1", 2L)?.historyTime != null)
        } finally {
            com.lanraragi.reader.client.api.LRRAuthManager.clear()
        }
    }
}
