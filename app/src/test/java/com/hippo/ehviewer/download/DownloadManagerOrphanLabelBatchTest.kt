package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadDbRepository
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
 * Tests for the batch orphan label insert optimization in
 * [DownloadManager.loadDataFromDb].
 *
 * Verifies that when downloads reference labels not present in the
 * DOWNLOAD_LABELS table, all orphan labels are persisted in a single
 * batch transaction rather than N individual inserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadManagerOrphanLabelBatchTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_orphan_test", Context.MODE_PRIVATE)
        )

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        ServiceRegistry.initializeForTest(
            data = object : com.hippo.ehviewer.module.IDataModule {
                override val downloadDbRepository get() = DownloadDbRepository(db.downloadDao(), db)
                override val downloadManager get() = throw NotImplementedError("set after init")
                override val favouriteStatusRouter get() = throw NotImplementedError("not needed")
                override val historyRepository get() = com.hippo.ehviewer.dao.HistoryRepository(db.browsingDao())
                override val profileRepository get() = throw NotImplementedError("not needed")
                override val quickSearchRepository get() = throw NotImplementedError("not needed")
                override val favoritesRepository get() = throw NotImplementedError("not needed")
                override val galleryDetailCache get() = throw NotImplementedError("not needed")
                override val spiderInfoCache get() = throw NotImplementedError("not needed")
                override fun clearGalleryDetailCache() {}
            }
        )

        LRRAuthManager.setServerUrl("http://localhost:3000")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    /**
     * With 50 orphan labels, loadDataFromDb should batch-insert them all
     * in a single transaction. After loading, mLabelSet and mLabelList
     * contain all 50 orphan labels.
     */
    @Test
    fun orphanLabels_50_allPresentAfterLoad() {
        val orphanCount = 50
        val orphanLabels = (1..orphanCount).map { "orphan-label-$it" }

        // Insert downloads with labels that do NOT exist in DOWNLOAD_LABELS
        runBlocking {
            for ((index, label) in orphanLabels.withIndex()) {
                val info = DownloadInfo().apply {
                    gid = (10000L + index)
                    token = "tok-$index"
                    title = "Orphan Gallery $index"
                    this.label = label
                    state = DownloadInfo.STATE_NONE
                    time = System.currentTimeMillis() + index
                }
                EhDB.putDownloadInfoAsync(info)
            }
        }

        // Verify no labels exist in DB before loading
        val labelsBeforeLoad = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertTrue("Labels table should be empty before manager init", labelsBeforeLoad.isEmpty())

        // Create DownloadManager — loadDataFromDb will encounter orphan labels
        val manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Verify all 50 orphan labels are in mLabelList
        val labelNames = manager.labelList.map { it.label }
        assertEquals(orphanCount, labelNames.size)
        for (label in orphanLabels) {
            assertTrue("mLabelList should contain '$label'", label in labelNames)
        }

        // Verify all 50 orphan labels are in mLabelSet (via containLabel)
        for (label in orphanLabels) {
            assertTrue("mLabelSet should contain '$label'", manager.containLabel(label))
        }

        // Verify all labels were persisted to DB
        val dbLabels = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertEquals(orphanCount, dbLabels.size)
        val dbLabelNames = dbLabels.map { it.label }
        for (label in orphanLabels) {
            assertTrue("DB should contain label '$label'", label in dbLabelNames)
        }
    }

    /**
     * After loading with orphan labels, mMap should have entries for each
     * orphan label, containing the correct downloads.
     */
    @Test
    fun orphanLabels_mMapContainsEntriesForEachLabel() {
        val orphanLabels = listOf("alpha", "beta", "gamma")

        runBlocking {
            for ((index, label) in orphanLabels.withIndex()) {
                // Insert 2 downloads per label
                for (j in 0..1) {
                    val info = DownloadInfo().apply {
                        gid = (20000L + index * 10 + j)
                        token = "tok-$index-$j"
                        title = "Gallery $label $j"
                        this.label = label
                        state = DownloadInfo.STATE_NONE
                        time = System.currentTimeMillis() + index * 10 + j
                    }
                    EhDB.putDownloadInfoAsync(info)
                }
            }
        }

        val manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Verify mMap has entries for each label with correct download counts
        for (label in orphanLabels) {
            val list = manager.getLabelDownloadInfoList(label)
            assertNotNull("mMap should have entry for '$label'", list)
            assertEquals("Label '$label' should have 2 downloads", 2, list!!.size)
        }
    }

    /**
     * If all downloads reference existing labels, no orphan insert should occur.
     * This verifies the batch path is a no-op when there are no orphans.
     */
    @Test
    fun noOrphanLabels_noExtraInserts() {
        // Pre-create labels
        runBlocking {
            EhDB.addDownloadLabelAsync("existing-1")
            EhDB.addDownloadLabelAsync("existing-2")
        }

        // Insert downloads referencing the existing labels
        runBlocking {
            for (i in 0..3) {
                val info = DownloadInfo().apply {
                    gid = (30000L + i)
                    token = "tok-$i"
                    title = "Gallery $i"
                    label = if (i % 2 == 0) "existing-1" else "existing-2"
                    state = DownloadInfo.STATE_NONE
                    time = System.currentTimeMillis() + i
                }
                EhDB.putDownloadInfoAsync(info)
            }
        }

        val manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Should have exactly the 2 pre-created labels, nothing more
        assertEquals(2, manager.labelList.size)
        val dbLabels = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertEquals(2, dbLabels.size)
    }

    /**
     * Mixed scenario: some labels exist, some are orphans.
     * Only the orphans should be batch-inserted.
     */
    @Test
    fun mixedLabels_onlyOrphansInserted() {
        // Pre-create one label
        runBlocking {
            EhDB.addDownloadLabelAsync("known")
        }

        // Insert downloads: some with "known", some with orphan labels
        val orphanLabels = listOf("orphan-a", "orphan-b", "orphan-c")
        runBlocking {
            val info1 = DownloadInfo().apply {
                gid = 40001L; token = "t1"; title = "G1"
                label = "known"; state = DownloadInfo.STATE_NONE
                time = System.currentTimeMillis()
            }
            EhDB.putDownloadInfoAsync(info1)

            for ((index, label) in orphanLabels.withIndex()) {
                val info = DownloadInfo().apply {
                    gid = (40010L + index); token = "t-$index"; title = "G-$label"
                    this.label = label; state = DownloadInfo.STATE_NONE
                    time = System.currentTimeMillis() + index + 1
                }
                EhDB.putDownloadInfoAsync(info)
            }
        }

        val manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // 1 known + 3 orphans = 4 labels total
        assertEquals(4, manager.labelList.size)
        assertTrue(manager.containLabel("known"))
        for (label in orphanLabels) {
            assertTrue("Should contain orphan '$label'", manager.containLabel(label))
        }

        // DB should also have 4 labels
        val dbLabels = runBlocking { EhDB.getAllDownloadLabelListAsync() }
        assertEquals(4, dbLabels.size)
    }

    /**
     * Multiple downloads referencing the same orphan label should result
     * in only one label insert, not one per download.
     */
    @Test
    fun duplicateOrphanLabel_insertedOnce() {
        // Insert 5 downloads all with the same orphan label
        runBlocking {
            for (i in 0..4) {
                val info = DownloadInfo().apply {
                    gid = (50000L + i); token = "t-$i"; title = "G-$i"
                    label = "shared-orphan"; state = DownloadInfo.STATE_NONE
                    time = System.currentTimeMillis() + i
                }
                EhDB.putDownloadInfoAsync(info)
            }
        }

        val manager = DownloadManager(context, testScope)
        runBlocking { manager.awaitInitAsync() }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Only 1 label should exist
        assertEquals(1, manager.labelList.size)
        assertEquals("shared-orphan", manager.labelList[0].label)

        // All 5 downloads should be in that label's list
        val list = manager.getLabelDownloadInfoList("shared-orphan")
        assertNotNull(list)
        assertEquals(5, list!!.size)
    }
}
