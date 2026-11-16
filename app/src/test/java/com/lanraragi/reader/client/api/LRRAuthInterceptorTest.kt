package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.server.ProfileLookupCache
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.ServerSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * targeting a configured LANraragi server profile (one profile here; the
 * multi-profile and base-path rules are in InterceptorProfilePathTest).
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
    private val profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    private var cache: ProfileLookupCache? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .addInterceptor(LRRAuthInterceptor { cache })
            .build()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("test_lrr_auth", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        SecureCredentialStore.initializeForTesting(prefs)
        cache = ProfileLookupCache(profiles, CoroutineScope(Dispatchers.Unconfined))
    }

    /** One profile (id 1) at [url] whose stored key is [apiKey]. */
    private fun serve(url: String, apiKey: String?) {
        profiles.value = listOf(ServerProfile(1L, "LRR", url, true))
        if (apiKey != null) ServerSession.setApiKeyForProfile(1L, apiKey)
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
        serve(baseUrl, null)
        assertNull(authHeaderSent())
    }

    @Test
    fun emptyApiKey_noHeader() {
        serve(baseUrl, "")
        assertNull(authHeaderSent())
    }

    @Test
    fun withApiKey_addsBearer() {
        serve(baseUrl, "test-key")

        val authHeader = authHeaderSent()
        assertNotNull("Authorization header should be present", authHeader)
        assertTrue(authHeader!!.startsWith("Bearer "))
        val decoded = Base64.getDecoder().decode(authHeader.removePrefix("Bearer "))
        assertEquals("test-key", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun requestToOtherHost_noHeader() {
        serve("https://different-host.example.com", "secret")
        assertNull(authHeaderSent())
    }

    @Test
    fun noProfile_noHeader() {
        ServerSession.setApiKeyForProfile(1L, "secret")
        assertNull(authHeaderSent())
    }

    /**
     * A request sent before Room's first emission waits for it instead of
     * going out without the key (the profiles are the only source).
     */
    @Test
    fun requestBeforeTheFirstEmission_waitsAndGetsTheKey() {
        val gate = CompletableDeferred<Unit>()
        ServerSession.setApiKeyForProfile(1L, "late-key")
        cache = ProfileLookupCache(
            flow {
                gate.await()
                emit(listOf(ServerProfile(1L, "LRR", baseUrl, true)))
            },
            CoroutineScope(Dispatchers.Default),
        )
        Thread { Thread.sleep(200); gate.complete(Unit) }.start()

        val header = authHeaderSent()

        assertEquals("late-key", String(Base64.getDecoder().decode(header!!.removePrefix("Bearer "))))
    }

    // ── W0-2 hardening tests ──────────────────────────────────────────────────

    /**
     * Same host:port but configured https / request http → credential downgrade,
     * must throw LRRPlaintextRefusedException before the request leaves the device.
     */
    @Test
    fun httpsConfigured_httpRequest_throwsPlaintextRefused() {
        // Configure HTTPS for the same host:port that MockWebServer listens on (HTTP).
        val mockUrl = server.url("/")
        serve("https://${mockUrl.host}:${mockUrl.port}", "secret")

        assertRefused(mockServerRequest())
        assertEquals("Request must not reach the server", 0, server.requestCount)
    }

    /**
     * Inverse: configured http but request https → also a scheme mismatch → reject.
     * Constructed directly because MockWebServer is HTTP-only.
     */
    @Test
    fun httpConfigured_httpsRequest_throwsPlaintextRefused() {
        serve("http://example.local:3000", "secret")

        assertRefused(Request.Builder().url("https://example.local:3000/api/info").build())
    }

    /**
     * Profile URL containing userInfo (`user:pass@host`) is treated as
     * malformed/malicious — every request must abort.
     */
    @Test
    fun serverUrlWithUserInfo_throwsPlaintextRefused() {
        val mockUrl = server.url("/")
        serve("http://attacker:pwd@${mockUrl.host}:${mockUrl.port}", "secret")

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
        val mockUrl = server.url("/")
        serve("http://${mockUrl.host}:${mockUrl.port}/#@evil.local/api", "secret")

        assertRefused(mockServerRequest())
        assertEquals(0, server.requestCount)
    }

    /**
     * Defense in depth: even if app code somehow constructs a request with
     * userInfo in the URL targeting our server, it must be rejected.
     */
    @Test
    fun requestUrlWithUserInfo_throwsPlaintextRefused() {
        val mockUrl = server.url("/")
        serve("http://${mockUrl.host}:${mockUrl.port}", "secret")

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
        // Configure a port that does not match MockWebServer's.
        val mockUrl = server.url("/")
        val otherPort = if (mockUrl.port == 9999) 9998 else 9999
        serve("http://${mockUrl.host}:$otherPort", "k")

        assertNull(authHeaderSent())
    }

    /** Host comparison must be case-insensitive (HttpUrl normalizes to lowercase). */
    @Test
    fun hostCaseInsensitive_match() {
        val mockUrl = server.url("/")
        // Mixed-case version of the host (HttpUrl will lowercase on parse).
        serve("http://${mockUrl.host.uppercase()}:${mockUrl.port}", "k")

        assertNotNull("Case-insensitive host match expected", authHeaderSent())
    }

    /** An unparseable profile URL matches no request, so its key goes nowhere. */
    @Test
    fun invalidProfileUrl_neverSendsTheKey() {
        serve("not-a-valid-url", "k")

        assertNull(authHeaderSent())
    }
}
