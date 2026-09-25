package com.lanraragi.reader.client.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.module.IDataModule
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

/**
 * The production path of both interceptors: the request's host:port is owned
 * by a profile in [ProfileLookupCache], so the key and the cleartext policy
 * come from that profile, not from the legacy active-server fallback that
 * LRRAuthInterceptorTest / LRRCleartextRejectionInterceptorTest reach.
 *
 * The legacy active server is deliberately the SAME http host:port, with its
 * own key and cleartext allowed: a request that fell back to it would carry
 * the legacy key and pass the cleartext gate. So every refusal and every
 * profile-specific header below can only come from the profile path.
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

    private val authClient = OkHttpClient.Builder().addInterceptor(LRRAuthInterceptor()).build()
    private val cleartextClient =
        OkHttpClient.Builder().addInterceptor(LRRCleartextRejectionInterceptor()).build()

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
        ServiceRegistry.initializeForTest(data = dataModule { cache })

        val prefs = ctx.getSharedPreferences("interceptor_profile_path", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        LRRAuthManager.initializeForTesting(prefs)
        LRRAuthManager.setServerUrl("http://$host:$port")
        LRRAuthManager.setApiKey("legacy-key")
        LRRAuthManager.setAllowCleartext(true)
    }

    @After
    fun tearDown() {
        // Leave the registry as the legacy-path suites expect: no cache hit.
        ServiceRegistry.initializeForTest(data = dataModule { throw NotImplementedError() })
        scope.cancel()
        db.close()
        server.shutdown()
        LRRAuthManager.clear()
    }

    private fun addProfile(url: String, key: String?, allowCleartext: Boolean = true): Long {
        val id = runBlocking {
            repo.insert(ServerProfile(name = "p", url = url, isActive = false, allowCleartext = allowCleartext))
        }
        if (key != null) LRRAuthManager.setApiKeyForProfile(id, key)
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
    fun cleartext_profileWithoutConsent_isRefused() {
        addProfile("http://$host:$port", key = null, allowCleartext = false)

        assertRefused<LRRCleartextRefusedException>(cleartextClient, "http://$host:$port/api/info")
    }

    @Test
    fun cleartext_httpRequestToAnHttpsOnlyProfile_isRefused() {
        addProfile("https://$host:$port", key = null)

        assertRefused<LRRCleartextRefusedException>(cleartextClient, "http://$host:$port/api/info")
    }

    private fun dataModule(lookup: () -> ProfileLookupCache): IDataModule = object : IDataModule {
        override val profileLookupCache get() = lookup()
        override val profileRepository get() = throw NotImplementedError("not needed")
        override val searchHistoryRepository get() = throw NotImplementedError("not needed")
        override val historyRepository get() = throw NotImplementedError("not needed")
        override val quickSearchRepository get() = throw NotImplementedError("not needed")
        override val favoritesRepository get() = throw NotImplementedError("not needed")
        override val downloadDbRepository get() = throw NotImplementedError("not needed")
        override val downloadManager get() = throw NotImplementedError("not needed")
        override val favouriteStatusRouter get() = throw NotImplementedError("not needed")
        override val archiveDetailCache get() = throw NotImplementedError("not needed")
        override val spiderInfoCache get() = throw NotImplementedError("not needed")
        override fun clearArchiveDetailCache() {}
    }
}
