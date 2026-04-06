package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DownloadManager] — the core download state management class.
 *
 * Uses Robolectric for Android Context + an in-memory Room database injected
 * into [EhDB] via reflection to avoid the AppDatabase singleton cache and
 * the Settings dependency in [EhDB.initialize].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: DownloadManager
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize Settings (needed by DownloadSettings accessed from DownloadManager)
        Settings.initialize(context)

        // Initialize CoroutineModule for ServiceRegistry (needed by DownloadManager default scope)
        ServiceRegistry.initializeForTest(CoroutineModule())

        // Create a test scope that runs on the unconfined dispatcher for synchronous execution
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Initialize LRRAuthManager — KeyStore unavailable under Robolectric,
        // but sActiveProfileId defaults to 0 which is fine for our tests.
        LRRAuthManager.initialize(context)
        // In Robolectric, EncryptedSharedPreferences always fails — inject plain prefs.
        // The method is 'internal' in Kotlin, so its JVM name may be mangled.
        // Search by prefix to handle both plain and mangled names.
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

        // Create in-memory Room database
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Inject into EhDB via reflection (bypass EhDB.initialize which has Settings dependency)
        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        // Configure a fake server URL so LRRDownloadWorker can be constructed
        // (ensureDownload creates workers that check for a non-null server URL)
        LRRAuthManager.setServerUrl("http://localhost:3000")

        // Create the manager under test with test scope and wait for async init to complete
        manager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { manager.awaitInitAsync() }
    }

    @After
    fun tearDown() {
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // A. Construction & Data Loading
    // ═══════════════════════════════════════════════════════════

    @Test
    fun constructor_loadsEmptyListFromEmptyDb() {
        // Manager was created in setUp with an empty DB
        assertTrue(manager.allDownloadInfoList.isEmpty())
        assertEquals(0, manager.downloadInfoList.size)
    }

    @Test
    fun constructor_loadsExistingDownloads() {
        // Insert downloads into DB before constructing a new manager
        val info1 = DownloadInfo().apply {
            gid = 1001L
            token = "token1"
            title = "Gallery One"
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        val info2 = DownloadInfo().apply {
            gid = 1002L
            token = "token2"
            title = "Gallery Two"
            state = DownloadInfo.STATE_FINISH
            time = System.currentTimeMillis() + 1
        }
        runBlocking {
            EhDB.putDownloadInfoAsync(info1)
            EhDB.putDownloadInfoAsync(info2)
        }

        val freshManager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { freshManager.awaitInitAsync() }

        assertEquals(2, freshManager.allDownloadInfoList.size)
        assertTrue(freshManager.containDownloadInfo(1001L))
        assertTrue(freshManager.containDownloadInfo(1002L))
    }

    @Test
    fun constructor_loadsExistingLabels() {
        // Insert labels into DB before constructing a new manager
        runBlocking {
            EhDB.addDownloadLabelAsync("Comics")
            EhDB.addDownloadLabelAsync("Manga")
        }

        val freshManager = DownloadManager(context, testScope)
        kotlinx.coroutines.runBlocking { freshManager.awaitInitAsync() }

        val labels = freshManager.labelList.map { it.label }
        assertTrue("Comics" in labels)
        assertTrue("Manga" in labels)
    }

    // ═══════════════════════════════════════════════════════════
    // B. Download Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Test
    fun startDownload_addsInfoToListAndNotifies() {
        val notifications = mutableListOf<String>()
        manager.addDownloadInfoListener(object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                notifications.add("add:${info.gid}")
            }
        })

        val gallery = GalleryInfo().apply {
            gid = 2001L
            token = "tok_2001"
            title = "Test Download"
        }
        // Start download with null (default) label
        manager.startDownload(gallery, null)

        assertTrue(manager.containDownloadInfo(2001L))
        // ensureDownload() may immediately promote WAIT -> DOWNLOAD
        val state = manager.getDownloadState(2001L)
        assertTrue(
            "Expected WAIT or DOWNLOAD, got $state",
            state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD
        )
        assertTrue(notifications.contains("add:2001"))
    }

    @Test
    fun startDownload_existingDownload_restartsIt() {
        // Add a download first with STATE_NONE via addDownload
        val gallery = GalleryInfo().apply {
            gid = 2002L
            token = "tok_2002"
            title = "Existing"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        assertEquals(DownloadInfo.STATE_NONE, manager.getDownloadState(2002L))

        // Now start it — should set to WAIT (ensureDownload may promote to DOWNLOAD)
        manager.startDownload(gallery, null)
        val state = manager.getDownloadState(2002L)
        assertTrue(
            "Expected WAIT or DOWNLOAD after restart, got $state",
            state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD
        )
    }

    @Test
    fun deleteDownload_removesFromAllLists() {
        val gallery = GalleryInfo().apply {
            gid = 2003L
            token = "tok_2003"
            title = "To Delete"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        assertTrue(manager.containDownloadInfo(2003L))

        manager.deleteDownload(2003L)

        assertFalse(manager.containDownloadInfo(2003L))
        assertNull(manager.getDownloadInfo(2003L))
    }

    @Test
    fun stopAllDownload_stopsAllActive() {
        // Add some downloads in WAIT state
        for (i in 1..3) {
            val gallery = GalleryInfo().apply {
                gid = (3000 + i).toLong()
                token = "tok_$i"
                title = "Gallery $i"
            }
            manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        }

        // Put them into wait state via startDownload
        for (i in 1..3) {
            val gallery = GalleryInfo().apply {
                gid = (3000 + i).toLong()
                token = "tok_$i"
                title = "Gallery $i"
            }
            manager.startDownload(gallery, null)
        }

        manager.stopAllDownload()

        for (i in 1..3) {
            val state = manager.getDownloadState((3000 + i).toLong())
            assertTrue(
                "Expected STATE_NONE or STATE_DOWNLOAD after stop, got $state",
                state == DownloadInfo.STATE_NONE || state == DownloadInfo.STATE_DOWNLOAD
            )
        }
    }

    @Test
    fun getDownloadState_returnsCorrectState() {
        // Non-existent returns INVALID
        assertEquals(DownloadInfo.STATE_INVALID, manager.getDownloadState(9999L))

        // Added returns the correct state
        val gallery = GalleryInfo().apply {
            gid = 2005L
            token = "tok_2005"
            title = "State Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_FINISH)
        assertEquals(DownloadInfo.STATE_FINISH, manager.getDownloadState(2005L))
    }

    // ═══════════════════════════════════════════════════════════
    // C. Label Management
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addLabel_createsNewLabelInDbAndList() {
        manager.addLabel("NewLabel")

        val labels = manager.labelList.map { it.label }
        assertTrue("NewLabel" in labels)

        // With Unconfined dispatcher, DB write completes synchronously
        // but give a tiny grace period for coroutine dispatch
        Thread.sleep(100)

        // Verify in DB
        val dbLabels = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertTrue(dbLabels.any { it.label == "NewLabel" })
    }

    @Test
    fun renameLabel_updatesInfoLabels() {
        manager.addLabel("OldName")

        // Add a download with that label
        val gallery = GalleryInfo().apply {
            gid = 4001L
            token = "tok_4001"
            title = "Labeled"
        }
        manager.addDownload(gallery, "OldName", DownloadInfo.STATE_NONE)

        // Rename the label
        manager.renameLabel("OldName", "NewName")

        // Verify label list updated
        val labels = manager.labelList.map { it.label }
        assertFalse("OldName" in labels)
        assertTrue("NewName" in labels)

        // Verify the download's label was updated
        val info = manager.getDownloadInfo(4001L)
        assertEquals("NewName", info?.label)
    }

    @Test
    fun removeLabel_movesInfoToDefault() {
        manager.addLabel("ToRemove")

        // Add a download with that label
        val gallery = GalleryInfo().apply {
            gid = 4002L
            token = "tok_4002"
            title = "Will Move"
        }
        manager.addDownload(gallery, "ToRemove", DownloadInfo.STATE_NONE)

        // Delete the label
        manager.deleteLabel("ToRemove")

        // Verify label removed
        val labels = manager.labelList.map { it.label }
        assertFalse("ToRemove" in labels)

        // Verify the download moved to default (null label)
        val info = manager.getDownloadInfo(4002L)
        assertNull("Download should have null label (default)", info?.label)

        // Verify it's in the default list
        assertTrue(manager.defaultDownloadInfoList.any { it.gid == 4002L })
    }

    @Test
    fun getLabelList_returnsAllLabels() {
        manager.addLabel("Alpha")
        manager.addLabel("Beta")
        manager.addLabel("Gamma")

        val labels = manager.labelList.map { it.label }
        assertEquals(3, labels.size)
        assertTrue("Alpha" in labels)
        assertTrue("Beta" in labels)
        assertTrue("Gamma" in labels)
    }

    // ═══════════════════════════════════════════════════════════
    // D. Listener Notifications
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addDownloadInfoListener_receivesCallbacks() {
        val events = mutableListOf<String>()
        val listener = object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                events.add("add:${info.gid}")
            }
            override fun onUpdateLabels() {
                events.add("updateLabels")
            }
        }
        manager.addDownloadInfoListener(listener)

        val gallery = GalleryInfo().apply {
            gid = 5001L
            token = "tok_5001"
            title = "Listener Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        manager.addLabel("ListenerLabel")

        assertTrue("add:5001" in events)
        assertTrue("updateLabels" in events)
    }

    @Test
    fun removeDownloadInfoListener_stopsReceiving() {
        val events = mutableListOf<String>()
        val listener = object : FakeDownloadInfoListener() {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                events.add("add:${info.gid}")
            }
        }
        manager.addDownloadInfoListener(listener)

        // First add — should be received
        val gallery1 = GalleryInfo().apply {
            gid = 5002L
            token = "tok_5002"
            title = "First"
        }
        manager.addDownload(gallery1, null, DownloadInfo.STATE_NONE)
        assertEquals(1, events.size)

        // Remove listener
        manager.removeDownloadInfoListener(listener)

        // Second add — should NOT be received
        val gallery2 = GalleryInfo().apply {
            gid = 5003L
            token = "tok_5003"
            title = "Second"
        }
        manager.addDownload(gallery2, null, DownloadInfo.STATE_NONE)
        assertEquals(1, events.size) // Still 1 — listener was removed
    }

    @Test
    fun reload_clearsAndReloadsFromDb() {
        // Insert a download directly to DB (bypassing manager) so we have
        // guaranteed data to reload from.
        val info = DownloadInfo().apply {
            gid = 5004L
            token = "tok_5004"
            title = "Reload Test"
            state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        runBlocking { EhDB.putDownloadInfoAsync(info) }

        val events = mutableListOf<String>()
        manager.addDownloadInfoListener(object : FakeDownloadInfoListener() {
            override fun onReload() {
                events.add("reload")
            }
            override fun onUpdateAll() {
                events.add("updateAll")
            }
        })

        // Reload — should pick up the DB-inserted download
        manager.reload()

        // Wait for async reload to complete and process Handler callbacks
        Thread.sleep(200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // After reload, our download should be present (DB may also contain
        // data from prior tests since the in-memory DB is shared in the suite)
        assertTrue(
            "allDownloadInfoList should not be empty after reload",
            manager.allDownloadInfoList.isNotEmpty()
        )
        assertTrue(manager.containDownloadInfo(5004L))
        assertTrue("reload" in events)
    }

    // ═══════════════════════════════════════════════════════════
    // E. Query Methods
    // ═══════════════════════════════════════════════════════════

    @Test
    fun containDownloadInfo_returnsTrueForExisting() {
        assertFalse(manager.containDownloadInfo(6001L))

        val gallery = GalleryInfo().apply {
            gid = 6001L
            token = "tok_6001"
            title = "Contain Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)

        assertTrue(manager.containDownloadInfo(6001L))
    }

    @Test
    fun getDownloadInfo_returnsCorrectInfo() {
        assertNull(manager.getDownloadInfo(6002L))

        val gallery = GalleryInfo().apply {
            gid = 6002L
            token = "tok_6002"
            title = "Info Test"
        }
        manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)

        val info = manager.getDownloadInfo(6002L)
        assertNotNull(info)
        assertEquals(6002L, info!!.gid)
        assertEquals("Info Test", info.title)
    }

    @Test
    fun getDownloadCount_returnsCorrectCount() {
        assertEquals(0, manager.allDownloadInfoList.size)

        for (i in 1..5) {
            val gallery = GalleryInfo().apply {
                gid = (6100 + i).toLong()
                token = "tok_$i"
                title = "Count $i"
            }
            manager.addDownload(gallery, null, DownloadInfo.STATE_NONE)
        }

        assertEquals(5, manager.allDownloadInfoList.size)
        assertEquals(5, manager.downloadInfoList.size)
    }

    // ═══════════════════════════════════════════════════════════
    // Helper: Fake DownloadInfoListener with no-op defaults
    // ═══════════════════════════════════════════════════════════

    private open class FakeDownloadInfoListener : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {}
        override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {}
        override fun onUpdateAll() {}
        override fun onReload() {}
        override fun onChange() {}
        override fun onRenameLabel(from: String, to: String) {}
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {}
        override fun onUpdateLabels() {}
    }
}
