package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 2026-09-21 §3: members of a tankoubon lose their standalone history
 * rows; the fact that they were read survives as ONE TANK_ pseudo-row whose
 * time is the newest member read. Download / favorite flags on a member row
 * are untouched — only the history subsystem flag moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class HistoryRepositoryTankFoldTest {

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

    private fun archive(arcid: String, profile: Long = 1L): Archive = Archive(
        arcid = arcid,
        title = "Title $arcid",
        tags = emptyMap(),
        pagecount = 10,
        progress = 0,
        extension = "zip",
        filename = "$arcid.zip",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = profile,
    )

    private suspend fun putHistoryAt(arcid: String, timeMs: Long, profile: Long = 1L) {
        dao.upsertHistory(arcid, profile, archive(arcid, profile).toJson(), timeMs, 0)
    }

    private fun Archive.toJson(): String =
        ArchiveLocalStateJson.encodeToString(Archive.serializer(), this)

    @Test
    fun fold_noMemberHistory_isNoOp() = runTest {
        val result = repo.foldMembersIntoTank(TANK, 1L, listOf(M1, M2), archive(TANK))
        assertNull(result)
        assertNull(dao.loadByArcidAndProfile(TANK, 1L))
    }

    @Test
    fun fold_createsTankRow_atNewestMemberTime_andClearsMembers() = runTest {
        putHistoryAt(M1, 1_000L)
        putHistoryAt(M2, 5_000L)

        val result = repo.foldMembersIntoTank(TANK, 1L, listOf(M1, M2), archive(TANK))

        assertEquals(setOf(M1, M2), result!!.foldedArcids.toSet())
        val tank = dao.loadByArcidAndProfile(TANK, 1L)
        assertNotNull(tank)
        assertEquals(5_000L, tank!!.historyTime)
        assertEquals("Title $TANK", ArchiveLocalStateJson.decodeFromString(Archive.serializer(), tank.archiveJson).title)
        assertNull(dao.loadByArcidAndProfile(M1, 1L))
        assertNull(dao.loadByArcidAndProfile(M2, 1L))
    }

    @Test
    fun fold_existingNewerTankRow_keepsItsTimeAndJson() = runTest {
        putHistoryAt(M1, 1_000L)
        dao.upsertHistory(TANK, 1L, archive(TANK).copy(title = "Session title").toJson(), 9_000L, 0)

        repo.foldMembersIntoTank(TANK, 1L, listOf(M1), archive(TANK))

        val tank = dao.loadByArcidAndProfile(TANK, 1L)!!
        assertEquals(9_000L, tank.historyTime)
        assertEquals("Session title", ArchiveLocalStateJson.decodeFromString(Archive.serializer(), tank.archiveJson).title)
        assertNull(dao.loadByArcidAndProfile(M1, 1L))
    }

    @Test
    fun fold_existingOlderTankRow_bumpsTimeOnly() = runTest {
        putHistoryAt(M1, 7_000L)
        dao.upsertHistory(TANK, 1L, archive(TANK).copy(title = "Session title").toJson(), 2_000L, 0)

        repo.foldMembersIntoTank(TANK, 1L, listOf(M1), archive(TANK))

        val tank = dao.loadByArcidAndProfile(TANK, 1L)!!
        assertEquals(7_000L, tank.historyTime)
        assertEquals("Session title", ArchiveLocalStateJson.decodeFromString(Archive.serializer(), tank.archiveJson).title)
    }

    @Test
    fun fold_memberWithDownloadFlag_keepsRow_dropsOnlyHistory() = runTest {
        putHistoryAt(M1, 1_000L)
        dao.upsertDownload(M1, 1L, archive(M1).toJson(), DownloadState.FINISH, 0, 1L, null, null, null)

        repo.foldMembersIntoTank(TANK, 1L, listOf(M1), archive(TANK))

        val row = dao.loadByArcidAndProfile(M1, 1L)
        assertNotNull(row)
        assertNull(row!!.historyTime)
        assertEquals(DownloadState.FINISH, row.downloadState)
    }

    @Test
    fun fold_ignoresOtherProfileMemberHistory() = runTest {
        putHistoryAt(M1, 1_000L, profile = 2L)

        assertNull(repo.foldMembersIntoTank(TANK, 1L, listOf(M1), archive(TANK)))
        assertNotNull(dao.loadByArcidAndProfile(M1, 2L))
    }

    private companion object {
        val M1 = "1".repeat(40)
        val M2 = "2".repeat(40)
        const val TANK = "TANK_1688000000"
    }
}
