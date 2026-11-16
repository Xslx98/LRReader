package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.awaitUntil
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DownloadDbRepository.observeDownloadStates], what the detail page and the
 * gallery list read instead of download listener callbacks: every download's
 * state, re-emitted only when a download changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadStatesFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private lateinit var repo: DownloadDbRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seen = Collections.synchronizedList(ArrayList<Map<String, DownloadState>>())

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.archiveLocalStateDao()
        repo = DownloadDbRepository(dao, db.downloadDao(), db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Suppress("DEPRECATION")
    private fun row(arcid: String, state: DownloadState?, historyTime: Long? = null) = runBlocking {
        dao.upsert(
            ArchiveLocalState(
                arcid = arcid,
                serverProfileId = 1L,
                archiveJson = """{"arcid":"$arcid","title":"T"}""",
                downloadState = state,
                downloadTime = state?.let { 1000L },
                historyTime = historyTime,
            )
        )
    }

    private fun collect() {
        scope.launch { repo.observeDownloadStates().collect { seen += it } }
        awaitUntil { seen.isNotEmpty() }
    }

    @Test
    fun emitsEveryDownloadsState_andIgnoresRowsWithoutADownload() {
        row("a", DownloadState.FINISH)
        row("h", null, historyTime = 5L)
        collect()

        assertEquals(mapOf("a" to DownloadState.FINISH), seen.last())
    }

    @Test
    fun aStateChangeReEmits_aHistoryWriteDoesNot() {
        row("a", DownloadState.WAIT)
        collect()

        row("h", null, historyTime = 9L)
        row("a", DownloadState.DOWNLOAD)
        awaitUntil { seen.last()["a"] == DownloadState.DOWNLOAD }

        assertEquals(
            "the history write on the shared table is not a new emission",
            listOf(mapOf("a" to DownloadState.WAIT), mapOf("a" to DownloadState.DOWNLOAD)),
            seen.toList(),
        )
    }
}
