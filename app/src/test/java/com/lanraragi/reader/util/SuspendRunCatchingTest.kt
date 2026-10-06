package com.lanraragi.reader.util

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Audit C33: suspendRunCatching never turns cancellation into a fallback value. */
class SuspendRunCatchingTest {

    @Test(timeout = 10_000)
    fun aCancelledCoroutineStopsInsteadOfCarryingOn() = runBlocking {
        var carriedOn = false
        val job = launch {
            suspendRunCatching { delay(60_000) }
            carriedOn = true
        }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse("the code after the block must not run", carriedOn)
    }

    @Test
    fun anOrdinaryFailureIsCaptured() = runBlocking {
        val result = suspendRunCatching<Int> { throw IOException("offline") }
        assertTrue(result.exceptionOrNull() is IOException)
    }
}
