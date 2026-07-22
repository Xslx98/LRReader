package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.MiscRoomDao
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRCleartextRefusedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [ServerConfigViewModel] — connection state management,
 * protocol auto-detection, LAN/WAN security detection, and server profile
 * persistence.
 *
 * Uses Robolectric for Android Context + in-memory Room database +
 * MockWebServer for HTTP interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ServerConfigViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)

        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_sc_test", Context.MODE_PRIVATE)
        )

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        server = MockWebServer()
        server.start()

        val testClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        // Create a minimal INetworkModule providing our test client
        val networkModule = createTestNetworkModule(testClient)

        val testDataModule = object : IDataModule {
            override val searchHistoryRepository get() = throw NotImplementedError("Not needed for these tests")
            override val profileRepository get() = ProfileRepository(db.miscDao())
            override val profileLookupCache get() = throw NotImplementedError("not needed")
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

        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            network = networkModule,
            data = testDataModule
        )

        // NetworkMonitor starts empty under Robolectric (no active network).
        // retryOnFailure checks isAvailable and fast-fails with LRROfflineException
        // if offline. Mark a network available so API calls can proceed.
        val monitor = ServiceRegistry.networkModule.networkMonitor
        monitor.handleAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // A. Initial state
    // ═══════════════════════════════════════════════════════════

    @Test
    fun initialState_connectingIsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.connecting.value)
    }

    // isInsecureWanUrl coverage lives in LRRUrlHelperLanAddressTest — the
    // ViewModel no longer wraps the shared predicate.

    // ═══════════════════════════════════════════════════════════
    // C. attemptConnection — duplicate guard
    // ═══════════════════════════════════════════════════════════

    @Test
    fun attemptConnection_whileAlreadyConnecting_isIgnored() {
        val vm = ServerConfigViewModel()

        // Manually set connecting to true via reflection
        val connectingField = ServerConfigViewModel::class.java.getDeclaredField("_connecting")
        connectingField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (connectingField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true

        // This should return immediately without changing state
        vm.attemptConnection("localhost", null, false)

        // Still connecting (was not reset by the guard)
        assertTrue(vm.connecting.value)
    }

    // ═══════════════════════════════════════════════════════════
    // D. attemptConnection — success with MockWebServer
    // ═══════════════════════════════════════════════════════════

    @Test
    fun attemptConnection_success_emitsConnectSuccessAndPersistsProfile() {
        server.enqueue(MockResponse().setBody(SERVER_INFO_JSON).setResponseCode(200))

        val vm = ServerConfigViewModel()
        val successes = mutableListOf<ServerConfigViewModel.ConnectSuccess>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.connectSuccess.collect { successes.add(it) }
        }

        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, null, true)

        drainCoroutines()

        assertTrue(successes.isNotEmpty())
        assertEquals("Test Server", successes.first().serverInfo.name)
        assertTrue(successes.first().navigateOnSuccess)
        assertFalse(vm.connecting.value)

        job.cancel()
    }

    @Test
    fun attemptConnection_failure_emitsConnectFailure() {
        // Return 401 (client error — no retry)
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = ServerConfigViewModel()
        val failures = mutableListOf<Exception>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.connectFailure.collect { failures.add(it) }
        }

        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, "test-key", false)

        drainCoroutines()

        assertTrue(failures.isNotEmpty())
        assertFalse(vm.connecting.value)

        job.cancel()
    }

    @Test
    fun attemptConnection_failure_neverTouchesGlobalAuthState() {
        // Simulate an existing working configuration.
        LRRAuthManager.setServerUrl("http://prior.example.com:3000")
        LRRAuthManager.setApiKey("prior-key")

        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = ServerConfigViewModel()
        val failures = mutableListOf<Exception>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch {
            vm.connectFailure.collect { failures.add(it) }
        }

        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, "candidate-key", false)

        drainCoroutines()

        assertTrue(failures.isNotEmpty())
        // NET-7: a test attempt must never write the global config at all —
        // there is nothing to roll back, even after a process kill mid-test.
        assertEquals("http://prior.example.com:3000", LRRAuthManager.getServerUrl())
        assertEquals("prior-key", LRRAuthManager.getApiKey())

        job.cancel()
    }

    @Test
    fun attemptConnection_explicitHttpWan_refusedFastWithoutTouchingGlobalAuth() {
        // Delegating to connectWithFallback makes onboarding inherit the LAN
        // gate: an explicit http:// WAN probe is refused synchronously (no
        // request is issued, the API key never leaves the device) with a
        // LRRCleartextRefusedException. Before delegation tryConnect had no gate
        // and would instead attempt a real cleartext connection. Global auth
        // stays intact.
        LRRAuthManager.setServerUrl("http://prior.example.com:3000")
        LRRAuthManager.setApiKey("prior-key")

        val vm = ServerConfigViewModel()
        val failures = mutableListOf<Exception>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val job = collectScope.launch { vm.connectFailure.collect { failures.add(it) } }

        vm.attemptConnection("http://198.51.100.7:3000", "candidate-key", true)

        // The gate fires synchronously (no network), so a short poll suffices;
        // before delegation this window would elapse with no SecurityException.
        val deadline = System.currentTimeMillis() + 3000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }

        assertTrue(
            "explicit WAN http must be refused with a cleartext-refusal error",
            failures.any { it is LRRCleartextRefusedException }
        )
        assertEquals("http://prior.example.com:3000", LRRAuthManager.getServerUrl())
        assertEquals("prior-key", LRRAuthManager.getApiKey())

        job.cancel()
    }

    @Test
    fun attemptConnection_success_sendsBearerHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SERVER_INFO_JSON))

        val vm = ServerConfigViewModel()
        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, "candidate-key", false)

        drainCoroutines()

        val recorded = server.takeRequest()
        val expected = "Bearer " + android.util.Base64.encodeToString(
            "candidate-key".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        assertEquals(expected, recorded.getHeader("Authorization"))
    }

    @Test
    fun attemptConnection_success_commitsCandidateKeyAndUrl() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SERVER_INFO_JSON))

        val vm = ServerConfigViewModel()
        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, "candidate-key", false)

        drainCoroutines()

        // NET-7: success is the single commit point for global auth state.
        assertEquals(baseUrl, LRRAuthManager.getServerUrl())
        assertEquals("candidate-key", LRRAuthManager.getApiKey())
    }

    @Test
    fun attemptConnection_success_bumpsServerConfigVersionForListAutoRefresh() {
        // Re-onboarding to a server must bump serverConfigVersion so
        // GalleryListScene.onResume auto-refreshes, matching the add/edit paths.
        val before = LRRAuthManager.serverConfigVersion
        server.enqueue(MockResponse().setResponseCode(200).setBody(SERVER_INFO_JSON))

        val vm = ServerConfigViewModel()
        vm.attemptConnection(server.url("").toString().removeSuffix("/"), "k", false)
        drainCoroutines()

        assertTrue(
            "onboarding success must bump serverConfigVersion",
            LRRAuthManager.serverConfigVersion > before
        )
    }

    @Test
    fun attemptConnection_roomWriteFails_reportsFailureAndLeavesGlobalAuthUntouched() {
        // The live global auth must switch only after the profile row persists.
        // If the Room insert fails, the active server URL/key must still point
        // at the old server, and the failure must surface (not silently switch).
        LRRAuthManager.setServerUrl("https://old.example.com")
        LRRAuthManager.setApiKey("old-key")
        server.enqueue(MockResponse().setResponseCode(200).setBody(SERVER_INFO_JSON))

        val throwingDao = object : MiscRoomDao by db.miscDao() {
            override suspend fun insertServerProfile(profile: ServerProfile): Long {
                throw IllegalStateException("simulated Room insert failure")
            }
        }
        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            network = ServiceRegistry.networkModule,
            data = throwingDataModule(ProfileRepository(throwingDao))
        )

        val vm = ServerConfigViewModel()
        val failures = mutableListOf<Exception>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.connectFailure.collect { failures.add(it) } }

        val baseUrl = server.url("").toString().removeSuffix("/")
        vm.attemptConnection(baseUrl, "candidate-key", false)
        drainCoroutines()

        assertTrue("Room failure must surface as connectFailure", failures.isNotEmpty())
        assertEquals("global URL must stay on the old server", "https://old.example.com", LRRAuthManager.getServerUrl())
        assertEquals("old-key", LRRAuthManager.getApiKey())
        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun throwingDataModule(repo: ProfileRepository): IDataModule = object : IDataModule {
        override val searchHistoryRepository get() = throw NotImplementedError("Not needed for these tests")
        override val profileRepository get() = repo
        override val profileLookupCache get() = throw NotImplementedError("not needed")
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

    /**
     * Drain IO coroutines and looper callbacks. The ViewModel uses
     * `viewModelScope.launch(Dispatchers.IO)` so the work happens on a
     * real thread. We wait for it to complete, then idle the looper
     * so that StateFlow emissions propagate.
     */
    private fun drainCoroutines() {
        // Connection tests make real HTTP calls to MockWebServer on
        // Dispatchers.IO. Give them time to complete + idle the looper
        // so that StateFlow emissions propagate.
        Thread.sleep(1000)
        ShadowLooper.idleMainLooper()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
    }

    private fun createTestNetworkModule(client: OkHttpClient): INetworkModule {
        val tempDir = File(context.cacheDir, "test-http-cache")
        tempDir.mkdirs()
        return object : INetworkModule {
            override val cache: Cache = Cache(tempDir, 1024L * 1024)
            override val hosts: Hosts = Hosts(context, "hosts_test.db")
            override val proxySelector: EhProxySelector = EhProxySelector()
            override val okHttpClient: OkHttpClient = client
            override val imageOkHttpClient: OkHttpClient = client
            override val longReadClient: OkHttpClient = client
            override val uploadClient: OkHttpClient = client
            override val networkMonitor: NetworkMonitor = NetworkMonitor(context)
        }
    }

    private companion object {
        val SERVER_INFO_JSON = """
            {
                "name": "Test Server",
                "motd": "Welcome",
                "version": "0.9.0",
                "version_name": "Test",
                "has_password": false,
                "debug_mode": false,
                "nofun_mode": false,
                "archives_per_page": 100,
                "server_resizes_images": false,
                "server_tracks_progress": false
            }
        """.trimIndent()
    }
}
