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
 * Tests for the blockingDb() main-thread guard in EhDB and direct async DAO operations.
 *
 * **W1-2**: [EhDB.blockingDb] now hard-throws on the main thread in debug builds (which is
 * what the unit-test variant `appReleaseDebug` builds against, so `BuildConfig.DEBUG == true`
 * here). The release-only `Log.w` fallback is not exercised by this test because we cannot
 * flip `BuildConfig.DEBUG` from a unit test.
 *
 * Uses in-memory Room directly (bypassing EhDB.initialize() which requires
 * Settings to be initialized). The throw fires before runBlocking is invoked, so the
 * `mainThreadThrows` test does not need a real `EhDB.sDatabase` instance.
 *
 * Ref: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html
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
     * W1-2 acceptance: any `@JvmStatic` blockingDb-bridged method must throw
     * [IllegalStateException] when invoked on the main thread in debug builds.
     *
     * `EhDB.queryGalleryTags(...)` is one of four remaining bridges (the others being
     * `getDownloadDirname`, `putDownloadDirname`, and `putDownloadInfo` — see
     * `docs/blockingdb-callsites.md`). The guard inside `blockingDb` runs *before*
     * `runBlocking { ... }`, so the test does not need `EhDB.initialize()` or a real
     * `sDatabase` — the throw fires first.
     */
    @Test
    fun blockingDb_mainThreadThrowsInDebug() {
        assertTrue(
            "BuildConfig.DEBUG must be true under appReleaseDebugUnitTest",
            BuildConfig.DEBUG
        )
        assertEquals(
            "Test must run on the main looper for this guard to fire",
            Looper.getMainLooper(),
            Looper.myLooper()
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            // Any @JvmStatic blockingDb-bridged method works; queryGalleryTags is the
            // simplest single-arg call site. The guard throws before runBlocking is
            // invoked, so we do not need EhDB.initialize() or a live sDatabase.
            EhDB.queryGalleryTags(0L)
        }
        assertTrue(
            "Exception message should mention main thread",
            ex.message?.contains("main thread") == true
        )
        assertTrue(
            "Exception message should point at the suspend variant",
            ex.message?.contains("Async") == true
        )
    }
}
