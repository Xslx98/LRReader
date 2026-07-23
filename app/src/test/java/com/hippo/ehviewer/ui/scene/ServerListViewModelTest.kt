package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.MiscRoomDao
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.SearchHistoryRepository
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
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
            override val searchHistoryRepository get() = SearchHistoryRepository(db.browsingDao(), db)
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

    /**
     * Start collecting [ServerListViewModel.uiEvent] and synchronously wait
     * until the collector has actually subscribed before returning.
     *
     * `_uiEvent` is a MutableSharedFlow with replay = 0 and
     * extraBufferCapacity = 4: a `tryEmit` issued while no subscribers are
     * active is silently dropped, not buffered. The previous
     * `eventScope.launch { collect { ... } }` pattern raced with the test
     * body — on CI's slower scheduler the `collect` coroutine had not yet
     * registered when `vm.deleteProfile(...)` fired, so the event was lost
     * and [awaitCondition] timed out.
     *
     * [onSubscription] runs its callback after the flow registers the
     * collector but before the first value is delivered, so completing the
     * deferred there guarantees the subscription is live by the time this
     * helper returns.
     */
    private fun collectEvents(vm: ServerListViewModel): CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent> {
        val events = CopyOnWriteArrayList<ServerListViewModel.ServerListUiEvent>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            vm.uiEvent
                .onSubscription { subscribed.complete(Unit) }
                .collect { events.add(it) }
        }
        runBlocking { subscribed.await() }
        return events
    }

    private fun insertProfile(name: String, url: String, isActive: Boolean = false): Long {
        return runBlocking { db.miscDao().insertServerProfile(ServerProfile(name = name, url = url, isActive = isActive)) }
    }

    /**
     * An [IDataModule] exposing [repo] as its profileRepository and throwing
     * for every other member, for tests that force a persistence failure.
     */
    private fun throwingDataModule(repo: ProfileRepository): IDataModule = object : IDataModule {
        override val searchHistoryRepository get() = SearchHistoryRepository(db.browsingDao(), db)
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

    // ── NET-7: connection tests must not touch global active auth ──

    @Test
    fun testAndAddProfile_failure_leavesGlobalAuthUntouched() {
        LRRAuthManager.setServerUrl("http://prior.example.com:3000")
        LRRAuthManager.setApiKey("prior-key")
        // 401 = permanent failure, no retry.
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        // Explicit http:// scheme → single probe, no https ladder.
        vm.testAndAddProfile("X", server.url("").toString().removeSuffix("/"), "candidate-key", true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.AddConnectionFailed }
        }
        assertEquals("http://prior.example.com:3000", LRRAuthManager.getServerUrl())
        assertEquals("prior-key", LRRAuthManager.getApiKey())
    }

    @Test
    fun testAndAddProfile_resolvesHttp_persistsCleartextAllowedSoProfileStaysUsable() {
        // A profile that resolves to plain HTTP must be persisted with
        // allowCleartext = true regardless of the gate flag, or the production
        // LRRCleartextRejectionInterceptor refuses every request to it. The
        // gate flag governs only the WAN-leak refusal (covered in
        // LRRUrlHelperConnectTest); the persisted flag must track the resolved
        // scheme. Passing allowCleartext = false here (LAN, gate-exempt) proves
        // the saved flag is not simply the passed-in value.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"name":"Mock","version":"0.9.8","archives_per_page":100}""")
        )

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val url = server.url("").toString().removeSuffix("/") // http://127.0.0.1:port (LAN)
        vm.testAndAddProfile("Mock", url, "k", allowCleartext = false)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.ProfileAdded }
        }

        val saved = runBlocking { db.miscDao().getAllServerProfiles() }.first { it.url == url }
        assertTrue("HTTP-resolved profile must persist allowCleartext=true", saved.allowCleartext)
    }

    @Test
    fun testAndAddProfile_roomInsertFails_emitsFailureAndLeavesGlobalAuthUntouched() {
        // A Room insert failure after a successful probe must surface
        // AddConnectionFailed (not silently soft-lock the dialog) and must not
        // have switched the live global auth to the new server.
        LRRAuthManager.setServerUrl("https://old.example.com")
        LRRAuthManager.setApiKey("old-key")
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"name":"New","version":"0.9.8","archives_per_page":100}"""))

        val throwingDao = object : MiscRoomDao by db.miscDao() {
            override suspend fun insertServerProfile(profile: ServerProfile): Long {
                throw IllegalStateException("simulated Room insert failure")
            }
        }
        ServiceRegistry.initializeForTest(
            data = throwingDataModule(ProfileRepository(throwingDao))
        )

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val newUrl = server.url("").toString().removeSuffix("/")
        vm.testAndAddProfile("New", newUrl, "new-key", allowCleartext = true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.AddConnectionFailed }
        }
        assertEquals("global URL must stay on the old server", "https://old.example.com", LRRAuthManager.getServerUrl())
        assertEquals("old-key", LRRAuthManager.getApiKey())
    }

    @Test
    fun testAndSaveEditedProfile_keystoreUnavailable_leavesRoomUnchanged() {
        // The connection test succeeds (the probe carries the key explicitly and
        // never touches the keystore), but committing the edit must not mutate
        // Room before the fragile secure-storage write is known to succeed.
        val id = insertProfile("Old Name", "https://old.example.com", isActive = true)
        val profile = ServerProfile(id = id, name = "Old Name", url = "https://old.example.com", isActive = true)
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"name":"New Server","version":"0.9.8","archives_per_page":100}"""))
        LRRAuthManager.simulateStorageUnavailableForTesting()

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val newUrl = server.url("").toString().removeSuffix("/")
        vm.testAndSaveEditedProfile(profile, 0, "New Name", newUrl, "new-key", allowCleartext = true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.SecureStorageError }
        }

        val fromDb = runBlocking { db.miscDao().getAllServerProfiles() }.first { it.id == id }
        assertEquals("edit must abort before the Room write", "https://old.example.com", fromDb.url)
        assertEquals("Old Name", fromDb.name)

        // Restore secure storage for tearDown / suite order.
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("server_vm_test_restore2", Context.MODE_PRIVATE)
        )
    }

    @Test
    fun testAndSaveEditedProfile_roomWriteFails_emitsErrorInsteadOfSilentSoftLock() {
        // Keystore writes and the connection test succeed, but the Room update
        // then fails. The outer catch must surface an error event so the edit
        // dialog re-enables instead of soft-locking silently with the Save
        // button stuck disabled.
        val id = insertProfile("Old Name", "https://old.example.com", isActive = true)
        val profile = ServerProfile(id = id, name = "Old Name", url = "https://old.example.com", isActive = true)
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"name":"New Server","version":"0.9.8","archives_per_page":100}"""))

        // A profile repository whose update throws, to force the post-keystore
        // persistence failure. Delegation forwards every other DAO call to the
        // real in-memory database.
        val throwingDao = object : MiscRoomDao by db.miscDao() {
            override suspend fun updateServerProfile(profile: ServerProfile) {
                throw IllegalStateException("simulated Room write failure")
            }
        }
        ServiceRegistry.initializeForTest(
            data = throwingDataModule(ProfileRepository(throwingDao))
        )

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val newUrl = server.url("").toString().removeSuffix("/")
        vm.testAndSaveEditedProfile(profile, 0, "New Name", newUrl, "new-key", allowCleartext = true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.EditConnectionFailed }
        }
    }

    @Test
    fun testAndSaveEditedProfile_roomWriteFails_leavesGlobalAuthUntouched() {
        // The live global auth must switch only AFTER the durable Room write
        // commits. If the Room update fails, the active server URL/key must
        // still point at the old server — otherwise traffic silently follows
        // the edited URL while the profile list shows the old one.
        LRRAuthManager.setServerUrl("https://old.example.com")
        LRRAuthManager.setApiKey("old-key")
        val id = insertProfile("Old Name", "https://old.example.com", isActive = true)
        val profile = ServerProfile(id = id, name = "Old Name", url = "https://old.example.com", isActive = true)
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"name":"New Server","version":"0.9.8","archives_per_page":100}"""))

        val throwingDao = object : MiscRoomDao by db.miscDao() {
            override suspend fun updateServerProfile(profile: ServerProfile) {
                throw IllegalStateException("simulated Room write failure")
            }
        }
        ServiceRegistry.initializeForTest(
            data = throwingDataModule(ProfileRepository(throwingDao))
        )

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val newUrl = server.url("").toString().removeSuffix("/")
        vm.testAndSaveEditedProfile(profile, 0, "New Name", newUrl, "new-key", allowCleartext = true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.EditConnectionFailed }
        }
        assertEquals("global URL must stay on the old server", "https://old.example.com", LRRAuthManager.getServerUrl())
        assertEquals("old-key", LRRAuthManager.getApiKey())
    }

    @Test
    fun testAndSaveEditedProfile_resolvesHttp_persistsCleartextAllowedFromResolvedScheme() {
        // Editing to a plain-HTTP URL (with the user's cleartext consent) must
        // persist allowCleartext = true so LRRCleartextRejectionInterceptor
        // keeps the profile usable — the saved flag tracks the resolved scheme,
        // not the stale stored flag. The stored profile starts allowCleartext =
        // false to prove the edit does not simply carry it over.
        val id = insertProfile("Old", "https://old.example.com", isActive = true)
        val profile = ServerProfile(
            id = id, name = "Old", url = "https://old.example.com",
            isActive = true, allowCleartext = false
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"name":"Mock","version":"0.9.8","archives_per_page":100}""")
        )

        val vm = ServerListViewModel()
        val events = collectEvents(vm)
        val newUrl = server.url("").toString().removeSuffix("/") // explicit http LAN, no fallback
        vm.testAndSaveEditedProfile(profile, 0, "New", newUrl, "k", allowCleartext = true)

        awaitCondition {
            events.any { it is ServerListViewModel.ServerListUiEvent.EditSaved }
        }

        val saved = runBlocking { db.miscDao().getAllServerProfiles() }.first { it.id == id }
        assertTrue("HTTP-resolved edit must persist allowCleartext=true", saved.allowCleartext)
        assertEquals(newUrl, saved.url)
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
        val events = collectEvents(vm)

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
    fun deleteProfile_cascadesSearchHistory() {
        val id = insertProfile("Doomed", "https://doomed.com")
        val profile = ServerProfile(id = id, name = "Doomed", url = "https://doomed.com")
        LRRAuthManager.setApiKeyForProfile(id, "test-key")
        val historyRepo = SearchHistoryRepository(db.browsingDao(), db)
        runBlocking { historyRepo.recordSearch(id, "doomed query") }

        val vm = ServerListViewModel()
        vm.loadProfiles()
        awaitCondition { vm.profiles.value.size == 1 }

        vm.deleteProfile(profile)

        awaitCondition { runBlocking { historyRepo.recentSearches(id) }.isEmpty() }
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
        val events = collectEvents(vm)

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
        val events = collectEvents(vm)

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
        val events = collectEvents(vm)

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
