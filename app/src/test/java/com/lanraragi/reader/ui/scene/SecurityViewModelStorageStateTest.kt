package com.lanraragi.reader.ui.scene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.ui.scene.SecurityViewModel.StorageState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit SEC-04: the lock screen offers "Reset app lock" only when the secure
 * store's init finished and failed, never while init is merely slow (the
 * reader timed out waiting). [LRRAuthManager] is a process-wide singleton, so
 * the init gate is restored in both setUp and tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SecurityViewModelStorageStateTest {

    private lateinit var ctx: Context
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(
        StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> println("contained: $t") }
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LRRAuthManager.resetInitGateForTesting()
        // Production has no store until init opens one; earlier tests leave one behind.
        LRRAuthManager.simulateStorageUnavailableForTesting()
    }

    @After
    fun tearDown() {
        scope.cancel()
        LRRAuthManager.clear()
        LRRAuthManager.resetInitGateForTesting()
    }

    @Test(timeout = 10_000)
    fun slowInit_offersOnlyARetry() {
        LRRAuthManager.scheduleInitialize(ctx, scope) // never advanced: init still running
        LRRAuthManager.mainThreadInitTimeoutMs = 100

        assertEquals(StorageState.STARTING, SecurityViewModel().storageState())
    }

    @Test(timeout = 10_000)
    fun initThatFailed_offersTheReset() {
        // Robolectric has no AndroidKeyStore: initialize() finishes on its failure branch.
        LRRAuthManager.scheduleInitialize(ctx, scope)
        scheduler.advanceUntilIdle()

        assertEquals(StorageState.UNAVAILABLE, SecurityViewModel().storageState())
    }

    @Test(timeout = 10_000)
    fun slowInitThatLaterOpens_becomesAvailableOnRetry() {
        LRRAuthManager.scheduleInitialize(ctx, scope)
        LRRAuthManager.mainThreadInitTimeoutMs = 100
        val viewModel = SecurityViewModel()
        assertEquals(StorageState.STARTING, viewModel.storageState())

        LRRAuthManager.initializeForTesting(ctx.getSharedPreferences("storage_state_test", Context.MODE_PRIVATE))

        assertEquals(StorageState.AVAILABLE, viewModel.storageState())
    }
}
