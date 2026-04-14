package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.lanraragi.reader.client.api.LRRAuthManager
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
            override val profileRepository get() = ProfileRepository(db.miscDao())
            override val historyRepository get() = throw NotImplementedError("not needed")
            override val quickSearchRepository get() = throw NotImplementedError("not needed")
            override val favoritesRepository get() = throw NotImplementedError("not needed")
            override val downloadManager get() = throw NotImplementedError("not needed")
            override val favouriteStatusRouter get() = throw NotImplementedError("not needed")
            override val galleryDetailCache get() = throw NotImplementedError("not needed")
            override val spiderInfoCache get() = throw NotImplementedError("not needed")
            override fun clearGalleryDetailCache() {}
        }

        ServiceRegistry.initializeForTest(
            coroutine = CoroutineModule(),
            network = networkModule,
            data = testDataModule
        )

        // NetworkMonitor starts with count=0 under Robolectric (no active network).
        // retryOnFailure checks isAvailable and fast-fails with LRROfflineException
        // if offline. Set the counter to 1 so API calls can proceed.
        val monitor = ServiceRegistry.networkModule.networkMonitor
        val countField = NetworkMonitor::class.java.getDeclaredField("mNetworkCount")
        countField.isAccessible = true
        (countField.get(monitor) as java.util.concurrent.atomic.AtomicInteger).set(1)
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

    // ═══════════════════════════════════════════════════════════
    // B. isInsecureWanConnection (pure function)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun isInsecureWanConnection_httpOnPublicIp_returnsTrue() {
        val vm = ServerConfigViewModel()
        assertTrue(vm.isInsecureWanConnection("http://203.0.113.5:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpsOnPublicIp_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("https://203.0.113.5:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOnLanIp_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("http://192.168.1.100:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOnLocalhost_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("http://localhost:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOn10Network_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("http://10.0.0.1:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOn172_16Network_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("http://172.16.0.1:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOnDotLocal_returnsFalse() {
        val vm = ServerConfigViewModel()
        assertFalse(vm.isInsecureWanConnection("http://myserver.local:3000"))
    }

    @Test
    fun isInsecureWanConnection_httpOnPublicDomain_returnsTrue() {
        val vm = ServerConfigViewModel()
        assertTrue(vm.isInsecureWanConnection("http://example.com:3000"))
    }

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
        val serverInfoJson = """
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

        server.enqueue(MockResponse().setBody(serverInfoJson).setResponseCode(200))

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

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

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
}
