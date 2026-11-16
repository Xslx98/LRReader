package com.lanraragi.reader.client.api

import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.server.ProfileLookupCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit
import com.lanraragi.reader.server.SecureCredentialStore

/**
 * Unit tests for [LRRCleartextRejectionInterceptor].
 *
 * Validates that:
 *   1. HTTPS requests always pass.
 *   2. HTTP requests are allowed iff host:port:scheme matches a configured
 *      profile AND that profile has allowCleartext = true (or is a LAN host).
 *   3. All other HTTP requests are rejected with [LRRCleartextRefusedException].
 *
 * For HTTPS pass-through we use a fake [Interceptor.Chain] that returns a canned
 * response — this exercises the interceptor's logic without needing an actual
 * TLS endpoint (the project does not depend on okhttp-tls).
 *
 * For HTTP allow paths we use MockWebServer (which serves HTTP by default).
 *
 * For HTTP reject paths the interceptor throws BEFORE chain.proceed() runs, so
 * no socket ever opens and we just pass any URL to a real OkHttpClient with the
 * interceptor installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LRRCleartextRejectionInterceptorTest {

    private lateinit var prefs: SharedPreferences
    private val profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    private val cache = ProfileLookupCache(profiles, CoroutineScope(Dispatchers.Unconfined))

    /** One active profile at [url] with the cleartext consent [allowCleartext]. */
    private fun serve(url: String, allowCleartext: Boolean) {
        profiles.value = listOf(ServerProfile(1L, "LRR", url, true, allowCleartext))
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("test_lrr_cleartext", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        // Inject plain prefs into the SecureCredentialStore singleton (object)
        val sPrefsField: Field = SecureCredentialStore::class.java.getDeclaredField("prefs")
        sPrefsField.isAccessible = true
        sPrefsField.set(SecureCredentialStore, prefs)
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
    }

    private fun client() = OkHttpClient.Builder()
        .addInterceptor(LRRCleartextRejectionInterceptor { cache })
        .build()

    private fun executeAndExpectRefused(url: String, message: String) {
        try {
            client().newCall(Request.Builder().url(url).build()).execute().close()
            fail("Expected LRRCleartextRefusedException for $message")
        } catch (e: LRRCleartextRefusedException) {
            assertNotNull("exception message should not be null", e.message)
        } catch (e: IOException) {
            // Subclass check — fail if it's a generic IOException, not our type
            if (e !is LRRCleartextRefusedException) {
                fail("Expected LRRCleartextRefusedException, got ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Fake chain that asserts `chain.proceed(request)` was called and returns
     * a canned 200 response. Used for HTTPS pass-through tests so we don't need
     * a real TLS server.
     */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody(null))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    // ────────────────────────────── HTTPS pass-through ──────────────────────────────

    @Test
    fun https_request_with_no_active_profile_passes_through() {
        val req = Request.Builder().url("https://example.test/api/info").build()
        val chain = FakeChain(req)
        val response = LRRCleartextRejectionInterceptor { cache }.intercept(chain)
        assertEquals(200, response.code)
        assertSame(req, chain.proceededRequest)
        response.close()
    }

    @Test
    fun https_request_to_third_party_passes_through_when_profile_set() {
        serve("http://lrr.local:3000", true)
        val req = Request.Builder().url("https://attacker.test/whatever").build()
        val chain = FakeChain(req)
        val response = LRRCleartextRejectionInterceptor { cache }.intercept(chain)
        assertEquals(200, response.code)
        assertSame(req, chain.proceededRequest)
        response.close()
    }

    // ────────────────────────────── HTTP reject paths ──────────────────────────────

    @Test
    fun http_request_with_no_active_profile_is_refused() {
        executeAndExpectRefused("http://example.test/api/info", "no active profile")
    }

    @Test
    fun http_request_to_third_party_host_is_refused_even_when_flag_true() {
        serve("http://lrr.local:3000", true)
        executeAndExpectRefused("http://attacker.test/api/info", "third-party host")
    }

    @Test
    fun http_request_to_active_wan_host_is_refused_when_flag_false() {
        serve("http://lrr.example.com:3000", false)
        executeAndExpectRefused("http://lrr.example.com:3000/api/info", "flag false")
    }

    /** Audit C27 (ruling R11): a LAN host needs no consent flag. */
    @Test
    fun http_request_to_active_lan_host_passes_without_flag() {
        serve("http://192.168.1.10:3000", false)
        val req = Request.Builder().url("http://192.168.1.10:3000/api/info").build()
        val chain = FakeChain(req)
        LRRCleartextRejectionInterceptor { cache }.intercept(chain)
        assertSame(req, chain.proceededRequest)
    }

    @Test
    fun http_request_to_active_host_is_refused_when_active_is_https() {
        // Active server is HTTPS, request is HTTP — scheme downgrade attempt
        serve("https://lrr.local", true)
        executeAndExpectRefused("http://lrr.local/api/info", "scheme downgrade")
    }

    @Test
    fun http_request_to_active_host_is_refused_when_port_differs() {
        // Active is :3000, request is default :80
        serve("http://lrr.local:3000", true)
        executeAndExpectRefused("http://lrr.local/api/info", "port mismatch")
    }

    // ────────────────────────────── HTTP allow paths ──────────────────────────────

    @Test
    fun http_request_to_active_host_passes_when_flag_true_and_host_matches() {
        val server = MockWebServer().apply { start() }
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            serve(baseUrl, true)

            server.enqueue(MockResponse().setBody("ok"))
            val resp = client().newCall(Request.Builder().url(server.url("/api/info")).build()).execute()
            assertEquals(200, resp.code)
            resp.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun http_request_to_active_host_with_explicit_port_succeeds() {
        val server = MockWebServer().apply { start() }
        try {
            val port = server.port
            serve("http://127.0.0.1:$port", true)

            server.enqueue(MockResponse().setBody("ok"))
            val resp = client().newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/info").build()
            ).execute()
            assertEquals(200, resp.code)
            resp.close()
        } finally {
            server.shutdown()
        }
    }
}
