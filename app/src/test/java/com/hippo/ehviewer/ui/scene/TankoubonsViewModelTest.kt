package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
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
 * Unit tests for [TankoubonsViewModel].
 *
 * Uses MockWebServer to simulate the LANraragi tankoubon API and Robolectric
 * for Android context. ServiceRegistry is initialized with test modules.
 * Harness mirrors [LRRCategoriesViewModelTest].
 *
 * The ViewModel dispatches work to `Dispatchers.IO`. Tests use [awaitCondition]
 * to wait for IO-dispatched coroutines to complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankoubonsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var ctx: Context
    private lateinit var eventScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("tankoubons_vm_test", Context.MODE_PRIVATE)
        )
        LRRAuthManager.setServerUrl(server.url("").toString().removeSuffix("/"))

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

        ServiceRegistry.initializeForTest(
            network = testNetworkModule,
            app = testAppModule
        )

        // Use a real dispatcher scope for event collection (not test scope)
        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        LRRAuthManager.clear()
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
     * Subscribe to [vm.uiEvent] on [eventScope] and **block until the
     * subscription is actually live**. The ViewModel's SharedFlow has
     * `replay = 0`, so an emission that fires before the collector is
     * registered is silently dropped. Always go through this helper instead
     * of `eventScope.launch { collect }` directly when the test needs to
     * observe a single subsequent emission.
     */
    private fun collectEvents(
        vm: TankoubonsViewModel
    ): CopyOnWriteArrayList<TankoubonsViewModel.TankUiEvent> {
        val events = CopyOnWriteArrayList<TankoubonsViewModel.TankUiEvent>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            vm.uiEvent
                .onSubscription { subscribed.complete(Unit) }
                .collect { events.add(it) }
        }
        runBlocking { subscribed.await() }
        return events
    }

    private fun tankJson(id: String, name: String, archiveCount: Int = 0, progress: Int = 0): String {
        val archives = (1..archiveCount).joinToString(",") { "\"a$it\"" }
        return """{"id":"$id","name":"$name","archives":[$archives],""" +
            """"summary":null,"tags":null,"progress":$progress}"""
    }

    private fun pageJson(total: Int, vararg tanks: String): String =
        """{"result":[${tanks.joinToString(",")}],"total":$total,"filtered":$total}"""

    // ── loadTankoubons ─────────────────────────────────────────────

    @Test
    fun loadTankoubons_singlePage_populatesTanks() {
        server.enqueue(MockResponse().setBody(pageJson(
            2,
            tankJson("TANK_0000000001", "Alpha", archiveCount = 2, progress = 5),
            tankJson("TANK_0000000002", "Beta")
        )))

        val vm = TankoubonsViewModel()
        vm.loadTankoubons()

        awaitCondition { vm.tanks.value.size == 2 }
        assertEquals("Alpha", vm.tanks.value[0].name)
        assertEquals(2, vm.tanks.value[0].archives.size)
        assertEquals(5, vm.tanks.value[0].progress)
        assertEquals("Beta", vm.tanks.value[1].name)
        awaitCondition { !vm.isLoading.value }
    }

    @Test
    fun loadTankoubons_multiPage_accumulatesUntilTotal() {
        // First page holds one of two tanks -> loop must fetch the next page.
        // LANraragi paginates /api/tankoubons 0-BASED: the first page is the
        // parameterless request (server default page 0) and the SECOND page is
        // ?page=1. A 1-based loop asks for page=1 first and gets an empty
        // result:[] with a correct total — the real-server symptom was a
        // permanently empty tank list (smoke 2026-07-05).
        server.enqueue(MockResponse().setBody(pageJson(
            2, tankJson("TANK_0000000001", "First")
        )))
        server.enqueue(MockResponse().setBody(pageJson(
            2, tankJson("TANK_0000000002", "Second")
        )))

        val vm = TankoubonsViewModel()
        vm.loadTankoubons()

        awaitCondition { vm.tanks.value.size == 2 }
        assertEquals("First", vm.tanks.value[0].name)
        assertEquals("Second", vm.tanks.value[1].name)
        assertEquals("Should have requested exactly two pages", 2, server.requestCount)
        assertEquals("/api/tankoubons", server.takeRequest().path)
        assertEquals("/api/tankoubons?page=1", server.takeRequest().path)
    }

    @Test
    fun loadTankoubons_pageTwoFailure_keepsPreviousTanksAndEmitsError() {
        // Seed a successful single-page load first
        server.enqueue(MockResponse().setBody(pageJson(
            1, tankJson("TANK_0000000001", "Stable")
        )))

        val vm = TankoubonsViewModel()
        vm.loadTankoubons()
        awaitCondition { vm.tanks.value.size == 1 }

        // Reload: page 1 succeeds (partial), page 2 blows up mid-pagination
        server.enqueue(MockResponse().setBody(pageJson(
            3, tankJson("TANK_0000000002", "Partial")
        )))
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val events = collectEvents(vm)
        vm.loadTankoubons()

        awaitCondition { events.any { it is TankoubonsViewModel.TankUiEvent.ShowError } }
        awaitCondition { !vm.isLoading.value }
        assertEquals("Failed reload must not publish a partial list", 1, vm.tanks.value.size)
        assertEquals("Stable", vm.tanks.value[0].name)
    }

    // ── create ─────────────────────────────────────────────

    @Test
    fun create_locked423_emitsTankLockedError() {
        server.enqueue(MockResponse().setResponseCode(423).setBody(
            """{"success":0,"error":"Locker is busy, try again later!"}"""
        ))

        val vm = TankoubonsViewModel()
        val events = collectEvents(vm)

        vm.create("NewTank")

        awaitCondition { events.isNotEmpty() }
        val error = events.filterIsInstance<TankoubonsViewModel.TankUiEvent.ShowError>().first()
        assertEquals(
            "423 must map to the dedicated locked message",
            ctx.getString(R.string.tank_locked),
            error.message
        )
    }

    @Test
    fun create_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody(
            """{"tankoubon_id":"TANK_0000000003","success":1}"""
        ))
        server.enqueue(MockResponse().setBody(pageJson(
            1, tankJson("TANK_0000000003", "NewTank")
        )))

        val vm = TankoubonsViewModel()
        val events = collectEvents(vm)

        vm.create("NewTank")

        awaitCondition { vm.tanks.value.isNotEmpty() }
        // The ShowSuccess event is delivered to the collector asynchronously
        // (on eventScope), so the tanks reload completing does not imply the
        // event has landed in [events] yet — poll the event itself too.
        awaitCondition { events.any { it is TankoubonsViewModel.TankUiEvent.ShowSuccess } }
        assertTrue("Should emit ShowSuccess",
            events.any { it is TankoubonsViewModel.TankUiEvent.ShowSuccess })
        assertEquals(1, vm.tanks.value.size)
        assertEquals("NewTank", vm.tanks.value[0].name)
        assertFalse("Create must not toggle the loading spinner", vm.isLoading.value)
    }
}
