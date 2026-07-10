/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NET-7: the connection-test client must not consult process-global auth or
 * cleartext state — candidate probes carry explicit auth and the caller's
 * isLanAddress gate owns cleartext policy for candidates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LRRUrlHelperTestClientTest {

    @Test
    fun buildTestClient_stripsAuthAndCleartextInterceptors() {
        val base = OkHttpClient.Builder()
            .addNetworkInterceptor(LRRCleartextRejectionInterceptor())
            .addNetworkInterceptor(LRRAuthInterceptor())
            .build()

        val test = LRRUrlHelper.buildTestClient(base)

        assertTrue(
            "test client must carry neither global-state interceptor",
            test.networkInterceptors.none {
                it is LRRAuthInterceptor || it is LRRCleartextRejectionInterceptor
            }
        )
        // The base client is untouched (production chain keeps both).
        assertEquals(2, base.networkInterceptors.size)
        // Short test timeouts survive the rebuild.
        assertEquals(5_000, test.connectTimeoutMillis)
        assertEquals(5_000, test.readTimeoutMillis)
    }
}
