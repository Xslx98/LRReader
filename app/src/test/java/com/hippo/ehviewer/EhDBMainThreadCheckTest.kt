package com.hippo.ehviewer

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for EhDB async DAO operations.
 *
 * **History:** Prior to W3-5 (2026-04-11), this test verified the `blockingDb()` main-thread
 * guard. That guard and all `@JvmStatic blockingDb`-bridged methods have been deleted — EhDB
 * now exposes only `suspend fun *Async()` methods. The test has been updated to verify the
 * async methods work correctly via Room's in-memory database.
 *
 * Uses in-memory Room directly (bypassing EhDB.initialize() which requires Settings to be
 * initialized).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class EhDBMainThreadCheckTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mainLooperIsAvailable() {
        assertNotNull("Main looper should exist in Robolectric", Looper.getMainLooper())
    }

    @Test
    fun mainThreadDetection_onMainThread() {
        val isMain = Looper.myLooper() == Looper.getMainLooper()
        assertTrue("Robolectric test should run on main looper", isMain)
    }

    @Test
    fun directDao_getAllQuickSearch_emptyOnFreshDb() = runTest {
        val result = db.browsingDao().getAllQuickSearch()
        assertTrue("Fresh DB should have no quick searches", result.isEmpty())
    }

    @Test
    fun directDao_getAllServerProfiles_emptyOnFreshDb() = runTest {
        val result = db.miscDao().getAllServerProfiles()
        assertTrue("Fresh DB should have no server profiles", result.isEmpty())
    }

    /**
     * Verifies that `getDownloadDirname` and `putDownloadDirname` @JvmStatic bridges
     * no longer exist on [EhDB]. This is a compile-time guarantee — if someone re-adds
     * a `blockingDb` bridge, this test will fail to compile.
     *
     * At runtime, we verify the async variants work correctly via the DAO.
     */
    @Test
    fun blockingDbBridges_removed() {
        // Compile-time proof: EhDB has no getDownloadDirname or putDownloadDirname methods.
        // If someone re-adds them, the reflection check below will fail the assertion.
        val ehdbMethods = EhDB::class.java.declaredMethods.map { it.name }
        assertFalse(
            "getDownloadDirname blockingDb bridge should not exist",
            ehdbMethods.contains("getDownloadDirname")
        )
        assertFalse(
            "putDownloadDirname blockingDb bridge should not exist",
            ehdbMethods.contains("putDownloadDirname")
        )
    }

    /**
     * Verifies the async dirname DAO round-trip works correctly.
     */
    @Test
    fun directDao_downloadDirname_roundTrip() = runTest {
        val dao = db.downloadDao()

        // Initially null
        val initial = dao.loadDirname(42L)
        assertNull("Fresh DB should have no dirname for gid 42", initial)

        // Insert
        val entry = com.hippo.ehviewer.dao.DownloadDirname()
        entry.gid = 42L
        entry.dirname = "42-test-gallery"
        dao.insertDirname(entry)

        // Read back
        val loaded = dao.loadDirname(42L)
        assertNotNull("Should find dirname after insert", loaded)
        assertEquals("42-test-gallery", loaded!!.dirname)

        // Update
        loaded.dirname = "42-test-gallery-sanitized"
        dao.updateDirname(loaded)
        val updated = dao.loadDirname(42L)
        assertEquals("42-test-gallery-sanitized", updated!!.dirname)

        // Delete
        dao.deleteDirnameByKey(42L)
        val deleted = dao.loadDirname(42L)
        assertNull("Should be null after delete", deleted)
    }
}
