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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the "reset reading progress" persistence layer.
 *
 * The download-list reset used to rewrite only legacy EhViewer-era
 * SpiderInfo files — which this app never creates — so the feature was a
 * complete no-op: the reader's actual resume sources (the reading_progress
 * SharedPreferences and the Room archive_json snapshot reconciled by
 * ReadingProgressReconciler) were untouched.
 * [HistoryRepository.resetReadingProgress] must zero the snapshot's
 * progress pair so the offline reconciler no longer resurrects the old
 * position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class HistoryRepositoryResetProgressTest {

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

    private fun archive(arcid: String): Archive = Archive(
        arcid = arcid,
        title = "Title $arcid",
        tags = emptyMap(),
        pagecount = 42,
        progress = 17,
        extension = "zip",
        filename = "$arcid.zip",
        thumbnailUrl = "",
        rating = 4f,
        isnew = false,
        lastreadtime = 1_600_000_000L,
        summary = null,
        serverProfileId = 1L,
    )

    private fun decodeStored(row: ArchiveLocalState): Archive =
        ArchiveLocalStateJson.decodeFromString(Archive.serializer(), row.archiveJson)

    @Test
    fun resetReadingProgress_zeroesProgressPair_preservesOtherFields() = runTest {
        repo.putHistoryInfo(archive("arc-reset"))

        repo.resetReadingProgress("arc-reset", 1L)

        val row = dao.loadByArcidAndProfile("arc-reset", 1L)
        assertNotNull(row)
        val stored = decodeStored(row!!)
        assertEquals(0, stored.progress)
        assertEquals(0L, stored.lastreadtime)
        // Everything else must survive the rewrite.
        assertEquals("Title arc-reset", stored.title)
        assertEquals(42, stored.pagecount)
        assertEquals(4f, stored.rating)
    }

    @Test
    fun resetReadingProgress_missingRow_isANoOp() = runTest {
        // Must not throw for arcids with no local row.
        repo.resetReadingProgress("never-seen", 1L)
    }

    @Test
    fun resetReadingProgress_wrongProfile_leavesRowUntouched() = runTest {
        repo.putHistoryInfo(archive("arc-other"))

        repo.resetReadingProgress("arc-other", 2L)

        val stored = decodeStored(dao.loadByArcidAndProfile("arc-other", 1L)!!)
        assertEquals(17, stored.progress)
    }
}
