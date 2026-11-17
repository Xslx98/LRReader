package com.lanraragi.reader.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.SearchHistoryRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubNetworkModule
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ServerProfileService]: a profile change the user started is committed in
 * full even when the screen that awaited it is gone. The step-by-step
 * commit rules (order, rollback, live session last), switching and the
 * delete cascade are covered through the ViewModels in
 * ServerConfigViewModelTest and ServerListViewModelTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ServerProfileServiceTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var service: ServerProfileService
    private lateinit var url: String
    private val cacheScope = com.lanraragi.reader.containedTestScope()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        url = server.url("").toString().removeSuffix("/")
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()

        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("server_profile_service_test", Context.MODE_PRIVATE))

        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        ServiceRegistry.initializeForTest(
            network = stubNetworkModule(client, File(ctx.cacheDir, "test-cache")),
            app = stubAppModule(ctx),
            data = object : IDataModule {
                override val searchHistoryRepository get() = SearchHistoryRepository(db.browsingDao(), db)
                override val profileRepository get() = ProfileRepository(db.miscDao())
                // Lags Room on purpose: a commit must return only once the
                // cache shows it, so the reads right after it see the new server.
                override val profileLookupCache = ProfileLookupCache(
                    ProfileRepository(db.miscDao()).observeAll().onEach { delay(150) },
                    cacheScope,
                )
                override val historyRepository get() = HistoryRepository(db.archiveLocalStateDao(), db)
                override val quickSearchRepository get() = throw UnsupportedOperationException("not needed")
                override val favoritesRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadDbRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadManager get() = throw UnsupportedOperationException("not needed")
                override val favouriteStatusRouter get() = throw UnsupportedOperationException("not needed")
                override val archiveDetailCache get() = throw UnsupportedOperationException("not needed")
                override fun clearArchiveDetailCache() {}
            },
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        service = ServerProfileService(scope)
        // Loaded before the commit, so a read after it cannot block until fresh data arrives.
        runBlocking { ServiceRegistry.dataModule.profileLookupCache.awaitInitialized() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        SecureCredentialStore.clear()
        db.close()
        server.shutdown()
    }

    @Test
    fun connectAndActivate_commitsWhenTheCallerIsCancelledMidTest() = runBlocking {
        server.enqueue(MockResponse().setBody(SERVER_INFO).setBodyDelay(300, TimeUnit.MILLISECONDS))

        val operation = startThenCancelCaller { service.connectAndActivate(url, "new-key", allowCleartext = false) }

        assertFalse(operation.isCancelled)
        val active = db.miscDao().getAllServerProfiles().single()
        assertEquals(url, active.url)
        assertEquals(active.id, ServerSession.getActiveProfileId())
        assertEquals(url, ServerSession.getServerUrl())
        assertEquals("new-key", ServerSession.getApiKey())
    }

    /** Entering a key while the re-enter-keys prompt is up clears it, as an edit already did. */
    @Test
    fun onboardingAndAdding_clearTheReauthPrompt_onceEveryProfileHasAKey() = runBlocking {
        ServerSession.markReauthIfProfilesUnprotected(listOf(99L))
        assertTrue(ServerSession.isNeedsReauthentication())

        server.enqueue(MockResponse().setBody(SERVER_INFO))
        service.connectAndActivate(url, "k1", allowCleartext = false)
        assertFalse("the onboarded profile has its key", ServerSession.isNeedsReauthentication())

        ServerSession.markReauthIfProfilesUnprotected(listOf(98L))
        server.enqueue(MockResponse().setBody(SERVER_INFO))
        service.connectAndAdd("Second", url.replace("127.0.0.1", "localhost"), "k2", allowCleartext = false)
        assertFalse("the added profile has its key", ServerSession.isNeedsReauthentication())
    }

    @Test
    fun connectAndAdd_commitsWhenTheCallerIsCancelledMidTest() = runBlocking {
        val oldId = db.miscDao().insertServerProfile(ServerProfile(name = "Old", url = "https://old.example.com", isActive = true))
        server.enqueue(MockResponse().setBody(SERVER_INFO).setBodyDelay(300, TimeUnit.MILLISECONDS))

        startThenCancelCaller { service.connectAndAdd("New", url, "new-key", allowCleartext = false) }

        val rows = db.miscDao().getAllServerProfiles()
        val added = rows.single { it.id != oldId }
        assertEquals(listOf(added.id), rows.filter { it.isActive }.map { it.id })
        assertEquals(url, ServerSession.getServerUrl())
        assertEquals("new-key", ServerSession.getApiKey())
    }

    @Test
    fun connectAndSaveEdit_savesWhenTheCallerIsCancelledMidTest() = runBlocking {
        val id = db.miscDao().insertServerProfile(ServerProfile(name = "Before", url = "https://old.example.com", isActive = true))
        val profile = db.miscDao().getAllServerProfiles().single { it.id == id }
        server.enqueue(MockResponse().setBody(SERVER_INFO).setBodyDelay(300, TimeUnit.MILLISECONDS))

        startThenCancelCaller { service.connectAndSaveEdit(profile, "After", url, "edited-key", allowCleartext = false) }

        val saved = db.miscDao().getAllServerProfiles().single()
        assertEquals("After", saved.name)
        assertEquals(url, saved.url)
        assertEquals(url, ServerSession.getServerUrl())
        assertEquals("edited-key", ServerSession.getApiKey())
    }

    /**
     * Starts [call] like a screen would, cancels that screen's wait once the
     * request is on the server, and returns the operation after it ended.
     */
    private suspend fun startThenCancelCaller(call: suspend () -> Unit): Deferred<*> {
        val caller: Job = CoroutineScope(Dispatchers.IO).launch { call() }
        withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
        val operation = scope.coroutineContext.job.children.single() as Deferred<*>
        caller.cancelAndJoin()
        withTimeout(10_000) { operation.join() }
        assertNull(operation.getCompletionExceptionOrNull())
        return operation
    }

    private companion object {
        const val SERVER_INFO = """{"name":"New","version":"0.9.8","archives_per_page":100}"""
    }
}
