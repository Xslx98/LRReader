package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the `lastreadtime` unit contract on the history
 * write paths: stored `archive_json.lastreadtime` is epoch SECONDS
 * (LANraragi server semantics — [com.lanraragi.reader.domain.Archive]
 * carries the server's own unit), while the `HISTORY_TIME` column stays
 * device milliseconds (`System.currentTimeMillis()`, used for list
 * ordering and display).
 *
 * Before unification, [HistoryRepository.putHistoryInfo] stamped
 * milliseconds into the JSON field, which fed the reading-progress
 * reconciler a timestamp ~1000x in the future and made a stale snapshot
 * beat a genuinely-newer local save (defended downstream by
 * [com.hippo.ehviewer.gallery.ReadingProgressReconciler.normalizeEpochSeconds],
 * which stays as pure defense for rows persisted by older versions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class HistoryRepositoryLastreadtimeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao
    private lateinit var repo: HistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    private fun archive(arcid: String, lastreadtime: Long = 0L): Archive = Archive(
        arcid = arcid,
        title = "Title $arcid",
        tags = emptyMap(),
        pagecount = 10,
        progress = 3,
        extension = "zip",
        filename = "$arcid.zip",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = lastreadtime,
        summary = null,
        serverProfileId = 1L,
    )

    private fun decodeStored(row: ArchiveLocalState): Archive =
        ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)

    @Test
    fun putHistoryInfo_stampsEpochSecondsIntoArchiveJson_andMillisIntoHistoryTime() = runTest {
        val beforeMs = System.currentTimeMillis()
        // A server-fetched Archive arrives with the server's own epoch-seconds
        // value; the put stamps "read now" over it — and must stay in seconds.
        repo.putHistoryInfo(archive("arc-put", lastreadtime = 1_600_000_000L))
        val afterMs = System.currentTimeMillis()

        val row = dao.loadByArcidAndProfile("arc-put", 1L)
        assertNotNull(row)
        val stored = decodeStored(row!!)
        assertTrue(
            "archive_json lastreadtime must be epoch seconds (was ${stored.lastreadtime})",
            stored.lastreadtime in (beforeMs / 1000L)..(afterMs / 1000L)
        )
        assertTrue(
            "HISTORY_TIME column must stay milliseconds (was ${row.historyTime})",
            row.historyTime!! in beforeMs..afterMs
        )
    }

    @Test
    fun putHistoryInfoList_convertsMillisecondViewTime_toEpochSecondLastreadtime() = runTest {
        val info = HistoryInfo()
        info.arcid = "arc-list"
        info.title = "T"
        info.serverProfileId = 1L
        info.time = 1_700_000_001_234L
        info.mode = 1

        repo.putHistoryInfoList(listOf(info))

        val row = dao.loadByArcidAndProfile("arc-list", 1L)
        assertNotNull(row)
        assertEquals(1_700_000_001L, decodeStored(row!!).lastreadtime)
        assertEquals(1_700_000_001_234L, row.historyTime)
    }
}
