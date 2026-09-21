package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalStateJson
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Orchestration contract of [TankMembershipSync] over real Room
 * repositories and fake side channels (spec 2026-09-21 §1/§3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankMembershipSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var downloadDb: DownloadDbRepository
    private lateinit var history: HistoryRepository

    private val progressStore = HashMap<String, Int>()
    private var shortcutTarget: Pair<String, Long>? = null
    private val published = ArrayList<Pair<String, Long>>()
    private var widgetRefreshes = 0

    private val sync by lazy {
        TankMembershipSync(
            downloadDb,
            history,
            object : TankMembershipSync.ProgressStore {
                override fun load(arcid: String): Int = progressStore[arcid] ?: 0
                override fun save(arcid: String, page0: Int) { progressStore[arcid] = page0 }
            },
            object : TankMembershipSync.ShortcutPort {
                override fun currentTarget(): Pair<String, Long>? = shortcutTarget
                override suspend fun publish(arcid: String, profileId: Long) { published += arcid to profileId }
            },
            refreshWidget = { widgetRefreshes++ },
        )
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        downloadDb = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined)
        history = HistoryRepository(db.archiveLocalStateDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun downloadInfo(arcid: String) = DownloadInfo().also {
        it.arcid = arcid
        it.title = arcid.take(4)
        it.state = DownloadState.FINISH
        it.serverProfileId = 1L
    }

    private suspend fun putHistory(arcid: String, timeMs: Long) {
        val json = ArchiveLocalStateJson.encodeToString(
            Archive.serializer(),
            Archive(arcid, "T $arcid", emptyMap(), 10, 0, "zip", "$arcid.zip", "", 0f, false, 0L, null, 1L),
        )
        db.archiveLocalStateDao().upsertHistory(arcid, 1L, json, timeMs, 0)
    }

    private fun truth(vararg ids: String, progress: Int = 1, name: String = "MyTank") =
        TankMembershipSync.TankTruth(TANK, name, ids.toList(), progress, pagecount = 0)

    @Test
    fun reconcilesGroup_andFoldsHistory_seedsProgressWhenLocalEmpty() = runTest {
        downloadDb.putDownloadInfo(downloadInfo(M1))
        downloadDb.putDownloadInfo(downloadInfo(M2))
        downloadDb.putTankGroup(TANK, 1L, "MyTank", listOf(M1))
        putHistory(M2, 5_000L)

        val changed = sync.sync(1L, BASE, listOf(truth(M1, M2, progress = 7)))

        assertTrue(changed)
        assertEquals(listOf(M1, M2), downloadDb.getTankGroupMemberIds(TANK))
        assertNull(db.archiveLocalStateDao().loadByArcidAndProfile(M2, 1L)!!.historyTime)
        assertEquals(5_000L, db.archiveLocalStateDao().loadByArcidAndProfile(TANK, 1L)!!.historyTime)
        assertEquals(6, progressStore[TANK])
        assertEquals(1, widgetRefreshes)
    }

    @Test
    fun foldNeverOverwritesLocalTankProgress() = runTest {
        putHistory(M1, 1_000L)
        progressStore[TANK] = 42

        sync.sync(1L, BASE, listOf(truth(M1, progress = 7)))

        assertEquals(42, progressStore[TANK])
    }

    @Test
    fun unreadServerProgressIsNotSeeded() = runTest {
        putHistory(M1, 1_000L)

        sync.sync(1L, BASE, listOf(truth(M1, progress = 1)))

        assertNull(progressStore[TANK])
    }

    @Test
    fun shortcutIsRepointedOnlyWhenItTargetedAFoldedMember() = runTest {
        putHistory(M1, 1_000L)
        shortcutTarget = M1 to 1L

        sync.sync(1L, BASE, listOf(truth(M1)))
        assertEquals(listOf(TANK to 1L), published)

        published.clear()
        putHistory(M2, 2_000L)
        shortcutTarget = "unrelated" to 1L
        sync.sync(1L, BASE, listOf(truth(M1, M2)))
        assertTrue(published.isEmpty())
    }

    @Test
    fun nothingToDo_reportsUnchanged_andLeavesWidgetAlone() = runTest {
        downloadDb.putTankGroup(TANK, 1L, "MyTank", listOf(M1))

        val changed = sync.sync(1L, BASE, listOf(truth(M1)))

        assertFalse(changed)
        assertEquals(0, widgetRefreshes)
    }

    @Test
    fun tankWithoutGroupRow_stillFoldsHistory() = runTest {
        putHistory(M1, 1_000L)

        val changed = sync.sync(1L, BASE, listOf(truth(M1)))

        assertTrue(changed)
        assertEquals("MyTank", history.getArchiveSnapshot(TANK, 1L)!!.title)
        assertNull(db.archiveLocalStateDao().loadByArcidAndProfile(M1, 1L))
    }

    private companion object {
        val M1 = "1".repeat(40)
        val M2 = "2".repeat(40)
        const val TANK = "TANK_1688000000"
        const val BASE = "http://10.0.2.2:3939"
    }
}
