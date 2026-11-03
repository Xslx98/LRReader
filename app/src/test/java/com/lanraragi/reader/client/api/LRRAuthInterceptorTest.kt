package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.ServerSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Unit tests for [LRRAuthInterceptor]: the Bearer token goes only to requests
 * targeting the configured LANraragi server.
 *
 * Plain SharedPreferences stand in for the KeyStore-backed store
 * ([SecureCredentialStore.initializeForTesting]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .addInterceptor(LRRAuthInterceptor())
            .build()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("test_lrr_auth", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        SecureCredentialStore.initializeForTesting(prefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
        SecureCredentialStore.clear()
    }

    /** Sends one request to the mock server and returns its Authorization header. */
    private fun authHeaderSent(): String? {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/api/info")).build()).execute().close()
        return server.awaitRequest(10).getHeader("Authorization")
    }

    private fun assertRefused(request: Request) {
        assertThrows(LRRPlaintextRefusedException::class.java) {
            client.newCall(request).execute().close()
        }
    }

    private fun mockServerRequest() = Request.Builder().url(server.url("/api/info")).build()

    @Test
    fun noApiKey_noHeader() {
        // API key is null by default after clearing prefs
        ServerSession.setServerUrl(baseUrl)
        assertNull(authHeaderSent())
    }

    @Test
    fun emptyApiKey_noHeader() {
        ServerSession.setApiKey("")
        ServerSession.setServerUrl(baseUrl)
        assertNull(authHeaderSent())
    }

    @Test
    fun withApiKey_addsBearer() {
        ServerSession.setApiKey("test-key")
        ServerSession.setServerUrl(baseUrl)

        val authHeader = authHeaderSent()
        assertNotNull("Authorization header should be present", authHeader)
        assertTrue(authHeader!!.startsWith("Bearer "))
        val decoded = Base64.getDecoder().decode(authHeader.removePrefix("Bearer "))
        assertEquals("test-key", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun requestToOtherHost_noHeader() {
        ServerSession.setApiKey("secret")
        ServerSession.setServerUrl("https://different-host.example.com")
        assertNull(authHeaderSent())
    }

    @Test
    fun noServerUrl_noHeader() {
        ServerSession.setApiKey("secret")
        // Server URL not set — getServerUrl() returns null
        assertNull(authHeaderSent())
    }

    // ── W0-2 hardening tests ──────────────────────────────────────────────────

    /**
     * Same host:port but configured https / request http → credential downgrade,
     * must throw LRRPlaintextRefusedException before the request leaves the device.
     */
    @Test
    fun httpsConfigured_httpRequest_throwsPlaintextRefused() {
        ServerSession.setApiKey("secret")
        // Configure HTTPS for the same host:port that MockWebServer listens on (HTTP).
        val mockUrl = server.url("/")
        ServerSession.setServerUrl("https://${mockUrl.host}:${mockUrl.port}")

        assertRefused(mockServerRequest())
        assertEquals("Request must not reach the server", 0, server.requestCount)
    }

    /**
     * Inverse: configured http but request https → also a scheme mismatch → reject.
     * Constructed directly because MockWebServer is HTTP-only.
     */
    @Test
    fun httpConfigured_httpsRequest_throwsPlaintextRefused() {
        ServerSession.setApiKey("secret")
        ServerSession.setServerUrl("http://example.local:3000")

        assertRefused(Request.Builder().url("https://example.local:3000/api/info").build())
    }

    /**
     * Stored server URL containing userInfo (`user:pass@host`) is treated as
     * malformed/malicious — every request must abort.
     */
    @Test
    fun serverUrlWithUserInfo_throwsPlaintextRefused() {
        ServerSession.setApiKey("secret")
        val mockUrl = server.url("/")
        ServerSession.setServerUrl("http://attacker:pwd@${mockUrl.host}:${mockUrl.port}")

        assertRefused(mockServerRequest())
        assertEquals(0, server.requestCount)
    }

    /**
     * Audit verification step 5: a malicious baseUrl
     * `http://attacker.com#@configured.local/api/foo` parses to host=attacker.com
     * with the rest in the fragment. Since the configured URL contains a fragment,
     * the interceptor must reject every request rather than silently leaking the
     * token to either host.
     */
    @Test
    fun serverUrlWithFragment_throwsPlaintextRefused() {
        ServerSession.setApiKey("secret")
        val mockUrl = server.url("/")
        ServerSession.setServerUrl("http://${mockUrl.host}:${mockUrl.port}/#@evil.local/api")

        assertRefused(mockServerRequest())
        assertEquals(0, server.requestCount)
    }

    /**
     * Defense in depth: even if app code somehow constructs a request with
     * userInfo in the URL targeting our server, it must be rejected.
     */
    @Test
    fun requestUrlWithUserInfo_throwsPlaintextRefused() {
        ServerSession.setApiKey("secret")
        val mockUrl = server.url("/")
        ServerSession.setServerUrl("http://${mockUrl.host}:${mockUrl.port}")

        // Build a request URL with userInfo via HttpUrl.Builder so OkHttp accepts it.
        val evilUrl = mockUrl.newBuilder()
            .username("alice")
            .password("hunter2")
            .addPathSegment("api")
            .addPathSegment("info")
            .build()
        assertRefused(Request.Builder().url(evilUrl).build())
        assertEquals(0, server.requestCount)
    }

    /**
     * Configured port differs from request port → MISMATCH → no header injected,
     * request still goes out unauthenticated.
     */
    @Test
    fun differentPort_noHeader() {
        ServerSession.setApiKey("k")
        // Configure a port that does not match MockWebServer's.
        val mockUrl = server.url("/")
        val otherPort = if (mockUrl.port == 9999) 9998 else 9999
        ServerSession.setServerUrl("http://${mockUrl.host}:$otherPort")

        assertNull(authHeaderSent())
    }

    /** Host comparison must be case-insensitive (HttpUrl normalizes to lowercase). */
    @Test
    fun hostCaseInsensitive_match() {
        ServerSession.setApiKey("k")
        val mockUrl = server.url("/")
        // Mixed-case version of the host (HttpUrl will lowercase on parse).
        ServerSession.setServerUrl("http://${mockUrl.host.uppercase()}:${mockUrl.port}")

        assertNotNull("Case-insensitive host match expected", authHeaderSent())
    }

    /**
     * A completely unparseable server URL must abort, never silently swallow the
     * token. This is a defense against corrupted prefs.
     */
    @Test
    fun invalidServerUrl_throwsPlaintextRefused() {
        ServerSession.setApiKey("k")
        ServerSession.setServerUrl("not-a-valid-url")

        assertRefused(mockServerRequest())
        assertEquals(0, server.requestCount)
    }
}
