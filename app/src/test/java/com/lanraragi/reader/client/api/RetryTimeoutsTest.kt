package com.lanraragi.reader.client.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** Audit C35: callers can opt out of retrying timeouts; the default still retries them. */
class RetryTimeoutsTest {

    @Test
    fun timeouts_are_retried_by_default() = runTest {
        var calls = 0
        val result = retryOnFailure(maxRetries = 2) {
            calls++
            if (calls < 3) throw SocketTimeoutException("timeout")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun timeouts_fail_fast_when_opted_out() = runTest {
        var calls = 0
        val e = runCatching {
            retryOnFailure(maxRetries = 2, retryTimeouts = false) {
                calls++
                throw SocketTimeoutException("timeout")
            }
        }.exceptionOrNull()
        assertTrue(e is SocketTimeoutException)
        assertEquals(1, calls)
    }

    @Test
    fun other_io_failures_are_still_retried_when_opted_out() = runTest {
        var calls = 0
        val result = retryOnFailure(maxRetries = 2, retryTimeouts = false) {
            calls++
            if (calls < 2) throw java.net.ConnectException("refused")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }
}
