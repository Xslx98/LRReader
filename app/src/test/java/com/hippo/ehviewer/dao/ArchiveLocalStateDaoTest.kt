package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for [ArchiveLocalStateDao] — the new unified per-archive
 * local state table introduced in L1.
 *
 * Validates that the @Entity is mappable by Room, that the IS NOT NULL
 * subsystem predicates filter rows correctly, and that the Flow query
 * emits on row changes. Migration coverage lives in
 * `MIGRATION_22_23_Test` (L1-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = android.app.Application::class
)
// dao.upsert() is @Deprecated for production (REPLACE wipes sibling columns)
// but is the right primitive for seeding fresh, distinct rows in these tests.
@Suppress("DEPRECATION")
class ArchiveLocalStateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveLocalStateDao

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.archiveLocalStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        arcid: String,
        serverProfileId: Long = 0L,
        archiveJson: String = """{"arcid":"$arcid"}""",
        downloadState: DownloadState? = null,
        downloadTime: Long? = null,
        downloadLabel: String? = null,
        historyTime: Long? = null,
        favoriteTime: Long? = null,
    ) = ArchiveLocalState(
        arcid = arcid,
        serverProfileId = serverProfileId,
        archiveJson = archiveJson,
        downloadState = downloadState,
        downloadTime = downloadTime,
        downloadLabel = downloadLabel,
        historyTime = historyTime,
        favoriteTime = favoriteTime,
    )

    @Test
    fun upsertAndLoad_roundTripsAllFields() = runTest {
        val state = ArchiveLocalState(
            arcid = "arc-1",
            serverProfileId = 7L,
            archiveJson = """{"arcid":"arc-1","title":"X"}""",
            downloadState = DownloadState.WAIT,
            downloadLegacy = 1,
            downloadTime = 1700000000000L,
            downloadLabel = "lab",
            downloadArchiveUri = "content://x",
            historyTime = 1700000001000L,
            historyMode = 2,
            favoriteTime = 1700000002000L,
        )
        dao.upsert(state)
        val loaded = dao.loadByArcid("arc-1")
        assertNotNull(loaded)
        assertEquals(state, loaded)
    }

    @Test
    fun loadByArcid_missingReturnsNull() = runTest {
        assertNull(dao.loadByArcid("nope"))
    }

    @Test
    fun deleteByArcid_removesRow() = runTest {
        dao.upsert(row("arc-d", downloadState = DownloadState.NONE, downloadTime = 1L))
        assertNotNull(dao.loadByArcid("arc-d"))
        dao.deleteByArcid("arc-d")
        assertNull(dao.loadByArcid("arc-d"))
    }

    @Test
    fun downloadList_filtersByDownloadStateNotNull() = runTest {
        dao.upsert(row("d-only", downloadState = DownloadState.NONE, downloadTime = 1000L))
        dao.upsert(row("h-only", historyTime = 2000L))
        dao.upsert(row("f-only", favoriteTime = 3000L))
        dao.upsert(row(
            "all-three",
            downloadState = DownloadState.FINISH,
            downloadTime = 4000L,
            historyTime = 4001L,
            favoriteTime = 4002L,
        ))

        val downloads = dao.getAllDownloads().map { it.arcid }
        assertEquals(listOf("all-three", "d-only"), downloads)
    }

    @Test
    fun historyList_filtersByHistoryTimeNotNull_orderDesc() = runTest {
        dao.upsert(row("h-old", historyTime = 1000L))
        dao.upsert(row("h-new", historyTime = 5000L))
        dao.upsert(row("h-mid", historyTime = 3000L))
        dao.upsert(row("not-h", downloadState = DownloadState.NONE, downloadTime = 9999L))

        val list = dao.getAllHistory().map { it.arcid }
        assertEquals(listOf("h-new", "h-mid", "h-old"), list)
    }

    @Test
    fun favoriteList_filtersByFavoriteTimeNotNull_orderDesc() = runTest {
        dao.upsert(row("f-old", favoriteTime = 1000L))
        dao.upsert(row("f-new", favoriteTime = 5000L))
        dao.upsert(row("not-f", historyTime = 9999L))

        val list = dao.getAllFavorites().map { it.arcid }
        assertEquals(listOf("f-new", "f-old"), list)
    }

    @Test
    fun observeDownloadsByServer_filtersByProfile() = runTest {
        dao.upsert(row("p7-a", serverProfileId = 7L, downloadState = DownloadState.NONE, downloadTime = 1L))
        dao.upsert(row("p7-b", serverProfileId = 7L, downloadState = DownloadState.WAIT, downloadTime = 2L))
        dao.upsert(row("p9-c", serverProfileId = 9L, downloadState = DownloadState.NONE, downloadTime = 3L))

        val list = dao.observeDownloadsByServer(7L).first()
        assertEquals(2, list.size)
        assertTrue(list.all { it.serverProfileId == 7L })
    }

    @Test
    fun resetTransientDownloadStates_clearsWaitAndDownloadOnly() = runTest {
        dao.upsert(row("waiting", downloadState = DownloadState.WAIT, downloadTime = 1L))
        dao.upsert(row("active", downloadState = DownloadState.DOWNLOAD, downloadTime = 2L))
        dao.upsert(row("done", downloadState = DownloadState.FINISH, downloadTime = 3L))
        dao.upsert(row("failed", downloadState = DownloadState.FAILED, downloadTime = 4L))
        dao.upsert(row("nonez", downloadState = DownloadState.NONE, downloadTime = 5L))
        dao.upsert(row("history", historyTime = 6L)) // not a download

        dao.resetTransientDownloadStates()

        assertEquals(DownloadState.NONE, dao.loadByArcid("waiting")!!.downloadState)
        assertEquals(DownloadState.NONE, dao.loadByArcid("active")!!.downloadState)
        assertEquals(DownloadState.FINISH, dao.loadByArcid("done")!!.downloadState)
        assertEquals(DownloadState.FAILED, dao.loadByArcid("failed")!!.downloadState)
        assertEquals(DownloadState.NONE, dao.loadByArcid("nonez")!!.downloadState)
        // A history-only row has no download state; the reset must not give it one.
        assertNull(dao.loadByArcid("history")!!.downloadState)
    }

    @Test
    fun observeAllDownloads_emitsOnInsert() = runTest {
        // Initial empty state
        val initial = dao.observeAllDownloads().first()
        assertEquals(0, initial.size)

        dao.upsert(row("d-1", downloadState = DownloadState.NONE, downloadTime = 1000L))

        val afterInsert = dao.observeAllDownloads().first()
        assertEquals(1, afterInsert.size)
        assertEquals("d-1", afterInsert[0].arcid)
    }

    // ── Composite-key per-profile write semantics (ADR-003) ──
    //
    // With PK (ARCID, SERVER_PROFILE_ID) each profile owns its own row.
    // A history/favorite write for a mirror copy read through another
    // profile creates/updates that profile's row and never touches a
    // different profile's download row — replacing the retired
    // `c72cc28d` CASE guard.

    @Test
    fun updateHistoryFields_isProfileScoped_leavesOtherProfileDownloadRowIntact() = runTest {
        dao.upsert(row("mirror", serverProfileId = 7L, downloadState = DownloadState.FINISH, downloadTime = 1L))

        // Reading the mirror copy through profile 9 records history on its
        // OWN (mirror, 9) row; the profile-7 download row is untouched.
        dao.insertOrIgnoreHistory("mirror", 9L, """{"arcid":"mirror","v":2}""", 2000L, 0)
        dao.updateHistoryFields("mirror", 9L, """{"arcid":"mirror","v":2}""", 2000L, 0)

        val downloadRow = dao.loadByArcidAndProfile("mirror", 7L)!!
        assertEquals(7L, downloadRow.serverProfileId)
        assertEquals(DownloadState.FINISH, downloadRow.downloadState)
        assertNull(downloadRow.historyTime)

        val historyRow = dao.loadByArcidAndProfile("mirror", 9L)!!
        assertEquals(2000L, historyRow.historyTime)
        assertNull(historyRow.downloadState)
        assertEquals("""{"arcid":"mirror","v":2}""", historyRow.archiveJson)
    }

    @Test
    fun historyWrite_underDifferentProfile_createsSeparateRow() = runTest {
        dao.upsert(row("h-row", serverProfileId = 7L, historyTime = 1000L))

        dao.insertOrIgnoreHistory("h-row", 9L, """{"arcid":"h-row"}""", 2000L, 0)
        dao.updateHistoryFields("h-row", 9L, """{"arcid":"h-row"}""", 2000L, 0)

        // The original (h-row, 7) row is untouched; (h-row, 9) is a new row.
        assertEquals(1000L, dao.loadByArcidAndProfile("h-row", 7L)!!.historyTime)
        assertEquals(2000L, dao.loadByArcidAndProfile("h-row", 9L)!!.historyTime)
        assertEquals(2, dao.getAllHistory().count { it.arcid == "h-row" })
    }

    @Test
    fun updateFavoriteFields_isProfileScoped_leavesOtherProfileDownloadRowIntact() = runTest {
        dao.upsert(row("mirror-f", serverProfileId = 7L, downloadState = DownloadState.FINISH, downloadTime = 1L))

        dao.insertOrIgnoreFavorite("mirror-f", 9L, """{"arcid":"mirror-f"}""", 3000L)
        dao.updateFavoriteFields("mirror-f", 9L, """{"arcid":"mirror-f"}""", 3000L)

        val downloadRow = dao.loadByArcidAndProfile("mirror-f", 7L)!!
        assertEquals(7L, downloadRow.serverProfileId)
        assertEquals(DownloadState.FINISH, downloadRow.downloadState)
        assertNull(downloadRow.favoriteTime)

        val favoriteRow = dao.loadByArcidAndProfile("mirror-f", 9L)!!
        assertEquals(3000L, favoriteRow.favoriteTime)
        assertNull(favoriteRow.downloadState)
    }
}
