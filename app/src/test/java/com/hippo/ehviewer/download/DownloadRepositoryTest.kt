package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DownloadRepository] — collection management and DB persistence.
 *
 * Uses Robolectric for Android Context + an in-memory Room database injected
 * into [EhDB] via reflection to avoid the AppDatabase singleton cache and
 * the Settings dependency in [EhDB.initialize].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadRepository
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize Settings (needed by EhDB internals)
        Settings.initialize(context)

        // Initialize CoroutineModule for ServiceRegistry
        ServiceRegistry.initializeForTest(CoroutineModule())

        // Create a test scope that runs on the unconfined dispatcher for synchronous execution
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Initialize LRRAuthManager
        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_test", Context.MODE_PRIVATE)
        )

        // Create in-memory Room database with synchronous executors
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        // Inject into EhDB via reflection
        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        LRRAuthManager.setServerUrl("http://localhost:3000")

        // Create the repository and wait for init to complete
        repo = DownloadRepository(context, testScope)
        repo.startLoading {}
        // With Unconfined dispatcher the load completes synchronously
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // 1. initialState_emptyDb
    // ═══════════════════════════════════════════════════════════

    @Test
    fun initialState_emptyDb() {
        assertTrue(repo.allInfoList.isEmpty())
        assertTrue(repo.allInfoMap.isEmpty())
        assertTrue(repo.labelList.isEmpty())
        assertTrue(repo.labelSet.isEmpty())
        assertTrue(repo.defaultInfoList.isEmpty())
        assertTrue(repo.initialized)
    }

    // ═══════════════════════════════════════════════════════════
    // 2. containDownloadInfo_returnsTrueForExisting
    // ═══════════════════════════════════════════════════════════

    @Test
    fun containDownloadInfo_returnsTrueForExisting() {
        assertFalse(repo.containDownloadInfo(1001L))

        val info = makeInfo(1001L, "tok1", "Gallery One")
        repo.addInfo(info)

        assertTrue(repo.containDownloadInfo(1001L))
    }

    // ═══════════════════════════════════════════════════════════
    // 3. addAndRemoveInfo
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addAndRemoveInfo() {
        val info = makeInfo(2001L, "tok2001", "Add Remove Test")
        repo.addInfo(info)

        assertTrue(repo.containDownloadInfo(2001L))
        assertEquals(1, repo.allInfoList.size)
        assertNotNull(repo.allInfoMap[2001L])

        val removedIndex = repo.removeInfo(info)
        assertTrue(removedIndex >= 0)
        assertFalse(repo.containDownloadInfo(2001L))
        assertTrue(repo.allInfoList.isEmpty())
        assertNull(repo.allInfoMap[2001L])
    }

    // ═══════════════════════════════════════════════════════════
    // 4. getInfoListForLabel_defaultReturnsDefaultList
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getInfoListForLabel_defaultReturnsDefaultList() {
        val list = repo.getInfoListForLabel(null)
        assertSame(repo.defaultInfoList, list)
    }

    // ═══════════════════════════════════════════════════════════
    // 5. getInfoListForLabel_namedReturnsLabelList
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getInfoListForLabel_namedReturnsLabelList() {
        repo.addLabel("MyLabel")
        val list = repo.getInfoListForLabel("MyLabel")
        assertNotNull(list)
        assertSame(repo.labelInfoMap["MyLabel"], list)

        // Non-existent label returns null
        assertNull(repo.getInfoListForLabel("NonExistent"))
    }

    // ═══════════════════════════════════════════════════════════
    // 6. addLabel_createsLabelAndMap
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addLabel_createsLabelAndMap() {
        val added = repo.addLabel("TestLabel")
        assertTrue(added)
        assertTrue(repo.containLabel("TestLabel"))
        assertNotNull(repo.getInfoListForLabel("TestLabel"))
        assertTrue(repo.labelList.any { it.label == "TestLabel" })
    }

    // ═══════════════════════════════════════════════════════════
    // 7. addLabel_duplicateIsNoOp
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addLabel_duplicateIsNoOp() {
        repo.addLabel("DupLabel")
        val sizeBefore = repo.labelList.size

        val added = repo.addLabel("DupLabel")
        assertFalse(added)
        assertEquals(sizeBefore, repo.labelList.size)
    }

    // ═══════════════════════════════════════════════════════════
    // 8. renameLabel_updatesInfoAndSets
    // ═══════════════════════════════════════════════════════════

    @Test
    fun renameLabel_updatesInfoAndSets() {
        repo.addLabel("OldName")

        // Add an info with that label
        val info = makeInfo(3001L, "tok3001", "Labeled").apply { label = "OldName" }
        repo.allInfoList.add(info)
        repo.allInfoMap[info.gid] = info
        repo.labelInfoMap["OldName"]!!.add(info)

        val affected = repo.renameLabel("OldName", "NewName")
        assertNotNull(affected)
        assertEquals(1, affected!!.size)

        // Old label gone, new exists
        assertFalse(repo.containLabel("OldName"))
        assertTrue(repo.containLabel("NewName"))

        // Info's label updated
        assertEquals("NewName", info.label)

        // labelInfoMap updated
        assertNull(repo.labelInfoMap["OldName"])
        assertNotNull(repo.labelInfoMap["NewName"])
        assertTrue(repo.labelInfoMap["NewName"]!!.contains(info))
    }

    // ═══════════════════════════════════════════════════════════
    // 9. deleteLabel_movesInfosToDefault
    // ═══════════════════════════════════════════════════════════

    @Test
    fun deleteLabel_movesInfosToDefault() {
        repo.addLabel("ToDelete")

        val info = makeInfo(4001L, "tok4001", "Will Move").apply {
            label = "ToDelete"
            time = 500L
        }
        repo.allInfoList.add(info)
        repo.allInfoMap[info.gid] = info
        repo.labelInfoMap["ToDelete"]!!.add(info)

        val affected = repo.deleteLabel("ToDelete")
        assertNotNull(affected)
        assertEquals(1, affected!!.size)

        // Label removed
        assertFalse(repo.containLabel("ToDelete"))
        assertFalse(repo.labelList.any { it.label == "ToDelete" })

        // Info moved to default list
        assertNull(info.label)
        assertTrue(repo.defaultInfoList.contains(info))
    }

    // ═══════════════════════════════════════════════════════════
    // 10. replaceInfo_updatesMapAndList
    // ═══════════════════════════════════════════════════════════

    @Test
    fun replaceInfo_updatesMapAndList() {
        val oldInfo = makeInfo(5001L, "tok5001", "Old")
        repo.addInfo(oldInfo)

        val newInfo = makeInfo(5002L, "tok5002", "New")
        repo.replaceInfo(newInfo, oldInfo)

        assertFalse(repo.containDownloadInfo(5001L))
        assertTrue(repo.containDownloadInfo(5002L))
        assertEquals("New", repo.getDownloadInfo(5002L)?.title)
    }

    // ═══════════════════════════════════════════════════════════
    // 11. getDownloadState_returnsCorrectState
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getDownloadState_returnsCorrectState() {
        // Non-existent returns INVALID
        assertEquals(DownloadInfo.STATE_INVALID, repo.getDownloadState(9999L))

        val info = makeInfo(6001L, "tok6001", "State Test").apply {
            state = DownloadInfo.STATE_FINISH
        }
        repo.addInfo(info)

        assertEquals(DownloadInfo.STATE_FINISH, repo.getDownloadState(6001L))
    }

    // ═══════════════════════════════════════════════════════════
    // 12. insertSorted_maintainsDateDescOrder
    // ═══════════════════════════════════════════════════════════

    @Test
    fun insertSorted_maintainsDateDescOrder() {
        val list = mutableListOf<DownloadInfo>()

        val timestamps = listOf(500L, 900L, 100L)
        for ((i, ts) in timestamps.withIndex()) {
            val info = makeInfo((7000 + i).toLong(), "tok_sort_$i", "Sort $i").apply {
                time = ts
            }
            DownloadRepository.insertSorted(list, info)
        }

        assertEquals(3, list.size)
        // Expected order: 900, 500, 100 (newest first)
        assertEquals(listOf(900L, 500L, 100L), list.map { it.time })

        // Verify DATE_DESC invariant
        for (i in 0 until list.size - 1) {
            assertTrue(
                "List not in DATE_DESC order at index $i: ${list[i].time} should >= ${list[i + 1].time}",
                list[i].time >= list[i + 1].time
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun makeInfo(gid: Long, token: String, title: String): DownloadInfo {
        return DownloadInfo().apply {
            this.gid = gid
            this.token = token
            this.title = title
            this.state = DownloadInfo.STATE_NONE
            this.time = System.currentTimeMillis()
        }
    }
}
