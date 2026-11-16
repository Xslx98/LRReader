package com.lanraragi.reader.client.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import com.lanraragi.reader.server.ProfileLookupCache
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.server.SecureCredentialStore

/**
 * Both interceptors over several profiles in a Room-backed
 * [ProfileLookupCache]: the request's host:port (and base path) picks the
 * profile, and the key and the cleartext policy come from that profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class InterceptorProfilePathTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: ProfileRepository
    private lateinit var cache: ProfileLookupCache
    private lateinit var scope: CoroutineScope
    private lateinit var host: String
    private var port = 0

    private val authClient = OkHttpClient.Builder().addInterceptor(LRRAuthInterceptor { cache }).build()
    private val cleartextClient =
        OkHttpClient.Builder().addInterceptor(LRRCleartextRejectionInterceptor { cache }).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        host = server.hostName
        port = server.port
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ProfileRepository(db.miscDao())
        scope = containedTestScope()
        cache = ProfileLookupCache(repo, scope)

        val prefs = ctx.getSharedPreferences("interceptor_profile_path", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SecureCredentialStore.initializeForTesting(prefs)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.shutdown()
        SecureCredentialStore.clear()
    }

    private fun addProfile(url: String, key: String?, allowCleartext: Boolean = true): Long {
        val id = runBlocking {
            repo.insert(ServerProfile(name = "p", url = url, isActive = false, allowCleartext = allowCleartext))
        }
        if (key != null) ServerSession.setApiKeyForProfile(id, key)
        awaitUntil(message = "profile never reached the lookup cache") { cache.findById(id) != null }
        return id
    }

    private fun sendThroughMock(client: OkHttpClient, url: String) {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }

    private inline fun <reified T : IOException> assertRefused(client: OkHttpClient, url: String) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            fail("expected ${T::class.simpleName}")
        } catch (e: IOException) {
            if (e !is T) fail("expected ${T::class.simpleName}, got $e")
        }
        assertEquals("a refused request must never reach the server", 0, server.requestCount)
    }

    // ── LRRAuthInterceptor ────────────────────────────────────────

    @Test
    fun auth_injectsTheOwningProfilesKey() {
        addProfile("http://$host:$port", key = "profile-key")

        sendThroughMock(authClient, "http://$host:$port/api/info")

        assertEquals(bearerAuthHeaderValue("profile-key"),
            server.awaitRequest().getHeader("Authorization"))
    }

    @Test
    fun auth_schemeDowngradeAgainstAnHttpsProfile_isRefused() {
        addProfile("https://$host:$port", key = "profile-key")

        assertRefused<LRRPlaintextRefusedException>(authClient, "http://$host:$port/api/info")
    }

    @Test
    fun auth_requestWithUserInfo_isRefused() {
        addProfile("http://$host:$port", key = "profile-key")

        assertRefused<LRRPlaintextRefusedException>(authClient, "http://user:pw@$host:$port/api/info")
    }

    // Audit C48 / SEC-08: same host:port but outside the profile's base path —
    // another app on the host, or a server URL escaping its instance.
    @Test
    fun auth_pathOutsideTheProfileBasePath_getsNoKey() {
        addProfile("http://$host:$port/lrr-a", key = "profile-key")

        sendThroughMock(authClient, "http://$host:$port/other-app/api/info")

        assertNull(server.awaitRequest().getHeader("Authorization"))
    }

    @Test
    fun auth_profileWithoutAKey_passesThroughUnauthenticated() {
        addProfile("http://$host:$port", key = null)

        sendThroughMock(authClient, "http://$host:$port/api/info")

        assertNull(server.awaitRequest().getHeader("Authorization"))
    }

    // ── LRRCleartextRejectionInterceptor ──────────────────────────

    @Test
    fun cleartext_allowedByTheOwningProfile_passes() {
        addProfile("http://$host:$port", key = null, allowCleartext = true)

        sendThroughMock(cleartextClient, "http://$host:$port/api/info")

        server.awaitRequest()
    }

    @Test
    fun cleartext_wanProfileWithoutConsent_isRefused() {
        // Refused before any connection: the gate is an application interceptor.
        addProfile("http://lrr.example.com:$port", key = null, allowCleartext = false)

        assertRefused<LRRCleartextRefusedException>(cleartextClient, "http://lrr.example.com:$port/api/info")
    }

    /**
     * Audit C27 (ruling R11): allowCleartext is the user's consent only; a LAN
     * host needs none, exactly as in the connect gate.
     */
    @Test
    fun cleartext_lanProfileWithoutConsent_passes() {
        addProfile("http://$host:$port", key = null, allowCleartext = false)

        sendThroughMock(cleartextClient, "http://$host:$port/api/info")

        server.awaitRequest()
    }

    @Test
    fun cleartext_httpRequestToAnHttpsOnlyProfile_isRefused() {
        addProfile("https://$host:$port", key = null)

        assertRefused<LRRCleartextRefusedException>(cleartextClient, "http://$host:$port/api/info")
    }
}
