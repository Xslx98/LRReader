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

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * NET-7: connectWithFallback must never touch the global active server URL —
 * probes are self-contained (explicit Bearer header, stripped test client).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LRRUrlHelperConnectTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val infoJson =
        """{"name":"T","version":"0.9.8","archives_per_page":100}"""

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("lrr_url_helper_test", Context.MODE_PRIVATE)
        )
        // Suite-order robustness: neutralize any offline NetworkMonitor left
        // in ServiceRegistry by an earlier class in the sandbox (retryOnFailure
        // treats a throwing stub as online via runCatching).
        ServiceRegistry.initializeForTest(
            network = object : INetworkModule {
                override val cache: Cache get() = throw UnsupportedOperationException()
                override val hosts: Hosts get() = throw UnsupportedOperationException()
                override val proxySelector: EhProxySelector get() = throw UnsupportedOperationException()
                override val okHttpClient: OkHttpClient get() = throw UnsupportedOperationException()
                override val imageOkHttpClient: OkHttpClient get() = throw UnsupportedOperationException()
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
        LRRAuthManager.clear()
    }

    private fun baseUrl() = server.url("").toString().removeSuffix("/")

    private fun LRRUrlHelper.ConnectResult.asSuccess() =
        this as LRRUrlHelper.ConnectResult.Success
    private fun LRRUrlHelper.ConnectResult.errorOrNull() =
        (this as? LRRUrlHelper.ConnectResult.Failure)?.error

    @Test
    fun explicitUrl_withKey_sendsBearerAndLeavesGlobalUrlUntouched() = runBlocking {
        LRRAuthManager.setServerUrl("http://prior.example.com:3000")
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))

        val result = LRRUrlHelper.connectWithFallback(client, baseUrl(), "candidate-key")

        assertEquals(baseUrl(), result.asSuccess().resolvedUrl)
        val expected = "Bearer " + Base64.encodeToString(
            "candidate-key".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        assertEquals(expected, server.takeRequest().getHeader("Authorization"))
        // The whole point of NET-7:
        assertEquals("http://prior.example.com:3000", LRRAuthManager.getServerUrl())
    }

    @Test
    fun explicitUrl_withoutKey_sendsNoAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        val result = LRRUrlHelper.connectWithFallback(client, baseUrl(), null)
        assertEquals(baseUrl(), result.asSuccess().resolvedUrl)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun schemelessLadder_httpsFailsHttpSucceeds_globalUrlStillUntouched() = runBlocking {
        LRRAuthManager.setServerUrl("http://prior.example.com:3000")
        // Schemeless 127.0.0.1:port → HTTPS attempt against the plaintext
        // MockWebServer fails the TLS handshake fast, then the LAN gate admits
        // the HTTP fallback which succeeds.
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))

        val hostPort = baseUrl().removePrefix("http://")
        val result = LRRUrlHelper.connectWithFallback(client, hostPort, "k").asSuccess()

        assertEquals("http://$hostPort", result.resolvedUrl)
        assertTrue(result.usedHttpFallback)
        assertEquals("http://prior.example.com:3000", LRRAuthManager.getServerUrl())
    }

    @Test
    fun explicitHttp_toNonLanHost_refusesBeforeSendingKey() = runBlocking {
        // An explicit http:// URL to a WAN host must be refused before any
        // request goes out — otherwise the Bearer key travels in cleartext.
        // isLanAddress is false for 198.51.100.x (TEST-NET-2), so the gate
        // fires and no socket is opened.
        val result = LRRUrlHelper.connectWithFallback(client, "http://198.51.100.7:3000", "secret")

        assertTrue(
            "explicit WAN http must be refused",
            result.errorOrNull() is LRRCleartextRefusedException
        )
    }

    @Test
    fun explicitHttp_toLanHost_stillAllowed() = runBlocking {
        // The gate must not block legitimate LAN cleartext testing.
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson))
        val result = LRRUrlHelper.connectWithFallback(client, baseUrl(), "k")

        assertEquals(baseUrl(), result.asSuccess().resolvedUrl)
    }

    @Test
    fun explicitHttp_toWanHost_withAllowCleartextOptIn_bypassesGate() = runBlocking {
        // A server the user explicitly opted into plain HTTP for
        // (allowCleartext=true, the add/edit dialog toggle) must NOT be
        // gate-refused — the probe proceeds, matching what production traffic
        // does for the saved profile. Against a TEST-NET address with no
        // server it then fails with a real network error, not a cleartext
        // refusal.
        val result = LRRUrlHelper.connectWithFallback(
            client, "http://198.51.100.7:3000", "secret", allowCleartext = true
        )

        val error = result.errorOrNull()
        assertTrue(
            "opted-in cleartext must bypass the gate",
            error != null && error !is LRRCleartextRefusedException
        )
    }

    @Test
    fun explicitHttp_toWanHost_withoutKey_stillRefusedWhenNotOptedIn() = runBlocking {
        // Deliberately NOT exempting the empty-key case: production
        // (LRRCleartextRejectionInterceptor) refuses cleartext for
        // allowCleartext=false profiles regardless of key, so admitting a
        // keyless WAN probe would only produce a profile whose traffic is
        // then blocked. Refuse unless opted in.
        val result = LRRUrlHelper.connectWithFallback(client, "http://198.51.100.7:3000", null)

        assertTrue(result.errorOrNull() is LRRCleartextRefusedException)
    }
}
