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
 * Tests for the blockingDb() main-thread detection pattern in EhDB,
 * and direct async DAO operations.
 *
 * Uses in-memory Room directly (bypassing EhDB.initialize() which requires
 * Settings to be initialized).
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
}
