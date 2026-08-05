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

import android.util.Base64
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * NET-7: connection-test probes carry their own Authorization header (test
 * clients strip LRRAuthInterceptor), using the interceptor's token format
 * `Bearer Base64(key)`.
 *
 * Plain [android.app.Application]: the manifest default (EhApplication) would
 * boot the full ServiceRegistry whose real NetworkMonitor reports offline
 * under Robolectric, tripping retryOnFailure's fast-fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LRRServerApiAuthHeaderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val infoJson =
        """{"name":"T","version":"0.9.8","archives_per_page":100}"""

    @Before
    fun setUp() {
        // Suite-order robustness: an earlier class in the same Robolectric
        // sandbox may leave ServiceRegistry holding a NetworkMonitor that
        // reports offline, tripping retryOnFailure's fast-fail. Install a
        // throwing stub — retryOnFailure's runCatching treats it as online.
        ServiceRegistry.initializeForTest(
            network = object : INetworkModule {
                override val cache: Cache get() = throw UnsupportedOperationException()
                override val proxySelector: EhProxySelector get() = throw UnsupportedOperationException()
                override val okHttpClient: OkHttpClient get() = throw UnsupportedOperationException()
                override val longReadClient: OkHttpClient get() = throw UnsupportedOperationException()
                override val uploadClient: OkHttpClient get() = throw UnsupportedOperationException()
                override val networkMonitor: NetworkMonitor get() = throw UnsupportedOperationException()
            }
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("").toString().removeSuffix("/")

    @Test
    fun withApiKey_sendsBearerToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        LRRServerApi.getServerInfo(client, baseUrl(), "secret-key")
        val recorded = server.takeRequest()
        val expected = "Bearer " + Base64.encodeToString(
            "secret-key".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        assertEquals(expected, recorded.getHeader("Authorization"))
    }

    @Test
    fun withNullKey_sendsNoAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        LRRServerApi.getServerInfo(client, baseUrl(), null)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun withEmptyKey_sendsNoAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        LRRServerApi.getServerInfo(client, baseUrl(), "")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun legacyTwoArgOverload_sendsNoAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        LRRServerApi.getServerInfo(client, baseUrl())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
