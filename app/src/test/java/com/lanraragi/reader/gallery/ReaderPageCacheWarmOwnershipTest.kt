package com.lanraragi.reader.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit C18: the detail preload dies with its screen and is never decoded twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ReaderPageCacheWarmOwnershipTest {

    private val arcid = "a".repeat(40)

    @After
    fun tearDown() {
        // Drain the slot so the next test starts empty.
        runBlocking { ReaderPageCache.consumeDecodedPage(arcid, 3)?.recycle() }
    }

    @Test
    fun cancelling_the_owner_cancels_the_app_scope_warm() = runBlocking {
        val warm = CoroutineScope(Dispatchers.Default + Job()).launch { awaitCancellation() }
        val started = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.Default) {
            started.complete(Unit)
            ReaderPageCache.joinOwned(warm)
        }
        started.await()
        withTimeout(5_000) { owner.cancelAndJoin() }
        withTimeout(5_000) { warm.join() }
        assertTrue(warm.isCancelled)
    }
}
