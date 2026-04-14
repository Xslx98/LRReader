package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [ServerListViewModel].
 *
 * Uses Robolectric + in-memory Room database + MockWebServer to exercise
 * profile CRUD, activation, and connection verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ServerListViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var server: MockWebServer
    private lateinit var eventScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val field = EhDB::class.java.getDeclaredField("sDatabase")
        field.isAccessible = true
        field.set(EhDB, db)

        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("server_vm_test", Context.MODE_PRIVATE)
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        val testNetworkModule = object : INetworkModule {
            override val cache: Cache get() = Cache(File(ctx.cacheDir, "test-cache"), 1024)
            override val hosts: Hosts get() = throw UnsupportedOperationException()
            override val proxySelector: EhProxySelector get() = throw UnsupportedOperationException()
            override val okHttpClient: OkHttpClient = client
            override val imageOkHttpClient: OkHttpClient = client
            override val longReadClient: OkHttpClient = client
            override val uploadClient: OkHttpClient = client
            override val networkMonitor: NetworkMonitor get() = throw UnsupportedOperationException()
        }

        val testAppModule = object : IAppModule {
            override fun getContext(): Context = ctx
            override fun initialize() {}
            override fun putGlobalStuff(o: Any): Int = 0
            override fun containGlobalStuff(id: Int): Boolean = false
            override fun getGlobalStuff(id: Int): Any? = null
            override fun removeGlobalStuff(id: Int): Any? = null
            override fun removeGlobalStuff(o: Any) {}
            override fun putTempCache(key: String, o: Any): String = key
            override fun containTempCache(key: String): Boolean = false
            override fun getTempCache(key: String): Any? = null
            override fun removeTempCache(key: String): Any? = null
        }

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
            network = testNetworkModule,
            app = testAppModule,
            data = testDataModule
        )

        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        LRRAuthManager.clear()
        db.close()
        server.shutdown()
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun insertProfile(name: String, url: String, isActive: Boolean = false): Long {
        return runBlocking { db.miscDao().insertServerProfile(ServerProfile(name = name, url = url, isActive = isActive)) }
    }

    // ── loadProfiles ───────────────────────────────────────────────

    @Test
    fun loadProfiles_populatesStateFlow() {
        insertProfile("Server A", "https://a.com")
        insertProfile("Server B", "https://b.com")

        val vm = ServerListViewModel()
        vm.loadProfiles()

        awaitCondition { vm.profiles.value.size == 2 }
        assertEquals(2, vm.profiles.value.size)
    }

    @Test
    fun loadProfiles_activeFirst() {
        insertProfile("Inactive", "https://inactive.com", isActive = false)
        insertProfile("Active", "https://active.com", isActive = true)

        val vm = ServerListViewModel()
        vm.loadProfiles()

        awaitCondition { vm.profiles.value.size == 2 }
        assertTrue("First profile should be active", vm.profiles.value[0].isActive)
        assertFalse("Second profile should be inactive", vm.profiles.value[1].isActive)
        assertEquals("Active", vm.profiles.value[0].name)
    }

    @Test
    fun loadProfiles_emptyDatabase_returnsEmptyList() {
        val vm = ServerListViewModel()
        vm.loadProfiles()

        awaitCondition { true }
        assertTrue("Profiles should be empty", vm.profiles.value.isEmpty())
    }

    // ── activateProfile ────────────────────────────────────────────

    @Test
    fun activateProfile_deactivatesOthersAndActivatesTarget() {
        val id1 = insertProfile("A", "https://a.com", isActive = true)
        val id2 = insertProfile("B", "https://b.com", isActive = false)
        val profileB = ServerProfile(id = id2, name = "B", url = "https://b.com", isActive = false)

        val vm = ServerListViewModel()
        val events = CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.activateProfile(profileB)

        awaitCondition { events.any { it is ServerListViewModel.ServerListUiEvent.ProfileActivated } }

        val allProfiles = runBlocking { db.miscDao().getAllServerProfiles() }
        val profileAFromDb = allProfiles.find { it.id == id1 }
        val profileBFromDb = allProfiles.find { it.id == id2 }

        assertFalse("Profile A should be deactivated", profileAFromDb!!.isActive)
        assertTrue("Profile B should be activated", profileBFromDb!!.isActive)

        val activated = events.filterIsInstance<ServerListViewModel.ServerListUiEvent.ProfileActivated>().first()
        assertEquals("B", activated.profile.name)
    }

    // ── deleteProfile ──────────────────────────────────────────────

    @Test
    fun deleteProfile_removesFromDatabase() {
        val id = insertProfile("Delete Me", "https://delete.com")
        val profile = ServerProfile(id = id, name = "Delete Me", url = "https://delete.com")
        LRRAuthManager.setApiKeyForProfile(id, "test-key")

        val vm = ServerListViewModel()
        vm.loadProfiles()
        awaitCondition { vm.profiles.value.size == 1 }

        vm.deleteProfile(profile)

        awaitCondition { vm.profiles.value.isEmpty() }
        assertTrue("Profile should be deleted", vm.profiles.value.isEmpty())
    }

    @Test
    fun deleteProfile_reloadsProfilesAfterDeletion() {
        insertProfile("Keep", "https://keep.com")
        val id2 = insertProfile("Remove", "https://remove.com")
        val toDelete = ServerProfile(id = id2, name = "Remove", url = "https://remove.com")
        LRRAuthManager.setApiKeyForProfile(id2, "key")

        val vm = ServerListViewModel()
        vm.loadProfiles()
        awaitCondition { vm.profiles.value.size == 2 }

        vm.deleteProfile(toDelete)
        awaitCondition { vm.profiles.value.size == 1 }

        assertEquals("Keep", vm.profiles.value[0].name)
    }

    @Test
    fun deleteProfile_secureStorageUnavailable_emitsSecureStorageError() {
        val id = insertProfile("Test", "https://test.com")
        val profile = ServerProfile(id = id, name = "Test", url = "https://test.com")

        LRRAuthManager.simulateStorageUnavailableForTesting()

        val vm = ServerListViewModel()
        val events = CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.deleteProfile(profile)

        awaitCondition { events.isNotEmpty() }
        assertTrue("Should emit SecureStorageError",
            events.any { it is ServerListViewModel.ServerListUiEvent.SecureStorageError })

        // Profile should NOT be deleted
        val remaining = runBlocking { db.miscDao().getAllServerProfiles() }
        assertEquals("Profile should still exist", 1, remaining.size)

        // Restore secure storage for tearDown
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("server_vm_test_restore", Context.MODE_PRIVATE)
        )
    }

    // ── verifyActiveProfile ────────────────────────────────────────

    @Test
    fun verifyActiveProfile_success_noErrorEmitted() {
        server.enqueue(MockResponse().setBody("""{
            "name": "My LANraragi",
            "motd": "Welcome!",
            "version": "0.9.21",
            "version_name": "Chaotic Century",
            "has_password": false,
            "debug_mode": false,
            "nofun_mode": false,
            "archives_per_page": 100,
            "server_resizes_images": false,
            "server_tracks_progress": false
        }"""))

        val vm = ServerListViewModel()
        val events = CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.verifyActiveProfile(server.url("").toString().removeSuffix("/"))

        // Wait for coroutine to complete
        Thread.sleep(2000)

        assertTrue("Should not emit any error event on success",
            events.none { it is ServerListViewModel.ServerListUiEvent.ShowToast })
    }

    @Test
    fun verifyActiveProfile_failure_emitsShowToast() {
        // Use 401 (4xx) so retryOnFailure fast-fails without retrying
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = ServerListViewModel()
        val events = CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.verifyActiveProfile(server.url("").toString().removeSuffix("/"))

        awaitCondition { events.any { it is ServerListViewModel.ServerListUiEvent.ShowToast } }
        assertTrue("Should emit ShowToast on verification failure",
            events.any { it is ServerListViewModel.ServerListUiEvent.ShowToast })
    }

    // ── Initial state ──────────────────────────────────────────────

    @Test
    fun initialState_profilesEmpty() {
        val vm = ServerListViewModel()
        assertTrue("Initial profiles should be empty", vm.profiles.value.isEmpty())
    }
}
