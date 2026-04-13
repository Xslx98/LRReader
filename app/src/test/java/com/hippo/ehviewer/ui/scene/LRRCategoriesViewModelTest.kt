package com.hippo.ehviewer.ui.scene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Unit tests for [LRRCategoriesViewModel].
 *
 * Uses MockWebServer to simulate the LANraragi category API and Robolectric
 * for Android context. ServiceRegistry is initialized with test modules.
 *
 * The ViewModel dispatches work to `Dispatchers.IO`. Tests use [awaitCondition]
 * to wait for IO-dispatched coroutines to complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRCategoriesViewModelTest {

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
            ctx.getSharedPreferences("categories_vm_test", Context.MODE_PRIVATE)
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

    // ── loadCategories ─────────────────────────────────────────────

    @Test
    fun loadCategories_success_populatesStateFlow() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"c1","name":"Favorites","archives":["a1"],"pinned":"1","search":""},
            {"id":"c2","name":"Dynamic","archives":[],"pinned":"0","search":"artist:foo"}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitCondition { vm.categories.value.size == 2 }
        assertEquals("Favorites", vm.categories.value[0].name)
        assertEquals("Dynamic", vm.categories.value[1].name)
    }

    @Test
    fun loadCategories_pinnedSortFirst() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"c1","name":"Unpinned","archives":[],"pinned":"0","search":""},
            {"id":"c2","name":"Pinned","archives":[],"pinned":"1","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitCondition { vm.categories.value.size == 2 }
        assertTrue("First item should be pinned", vm.categories.value[0].isPinned())
        assertFalse("Second item should not be pinned", vm.categories.value[1].isPinned())
        assertEquals("Pinned", vm.categories.value[0].name)
    }

    @Test
    fun loadCategories_skipsNamelessEntries() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"c1","name":"Valid","archives":[],"pinned":"0","search":""},
            {"id":"c2","name":"","archives":[],"pinned":"0","search":""},
            {"id":"c3","name":null,"archives":[],"pinned":"0","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitCondition { !vm.isLoading.value }
        assertEquals("Should skip empty/null names", 1, vm.categories.value.size)
        assertEquals("Valid", vm.categories.value[0].name)
    }

    @Test
    fun loadCategories_setsLoadingState() {
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        assertFalse("Should not be loading initially", vm.isLoading.value)

        vm.loadCategories()
        assertTrue("Should be loading after loadCategories call", vm.isLoading.value)

        awaitCondition { !vm.isLoading.value }
    }

    @Test
    fun loadCategories_error_emitsShowErrorEvent() {
        // Use 401 (4xx) so retryOnFailure fast-fails without retrying
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = LRRCategoriesViewModel()
        val events = CopyOnWriteArrayList<LRRCategoriesViewModel.CategoriesUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.loadCategories()

        awaitCondition { !vm.isLoading.value }
        awaitCondition { events.isNotEmpty() }
        assertTrue("Should have emitted an error event",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowError })
    }

    @Test
    fun loadCategories_emptyList_setsEmptyCategories() {
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitCondition { !vm.isLoading.value }
        assertTrue("Categories should be empty", vm.categories.value.isEmpty())
    }

    // ── createCategory ─────────────────────────────────────────────

    @Test
    fun createCategory_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody(
            """{"category_id":"new_cat","operation":"create_category","success":1}"""
        ))
        server.enqueue(MockResponse().setBody("""[
            {"id":"new_cat","name":"NewCat","archives":[],"pinned":"0","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        val events = CopyOnWriteArrayList<LRRCategoriesViewModel.CategoriesUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.createCategory("NewCat", null, false)

        awaitCondition { vm.categories.value.isNotEmpty() }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
        assertEquals(1, vm.categories.value.size)
        assertEquals("NewCat", vm.categories.value[0].name)
    }

    // ── editCategory ─────────────────────────────────────────────

    @Test
    fun editCategory_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))
        server.enqueue(MockResponse().setBody("""[
            {"id":"c1","name":"Edited","archives":[],"pinned":"1","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        val events = CopyOnWriteArrayList<LRRCategoriesViewModel.CategoriesUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.editCategory("c1", "Edited", null, true)

        awaitCondition { vm.categories.value.isNotEmpty() }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
        assertEquals("Edited", vm.categories.value[0].name)
    }

    // ── deleteCategory ─────────────────────────────────────────────

    @Test
    fun deleteCategory_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        val events = CopyOnWriteArrayList<LRRCategoriesViewModel.CategoriesUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.deleteCategory("c1")

        awaitCondition { events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess } }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
    }

    @Test
    fun deleteCategory_error_emitsShowError() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val vm = LRRCategoriesViewModel()
        val events = CopyOnWriteArrayList<LRRCategoriesViewModel.CategoriesUiEvent>()
        eventScope.launch { vm.uiEvent.collect { events.add(it) } }

        vm.deleteCategory("nonexistent")

        awaitCondition { events.isNotEmpty() }
        assertTrue("Should emit ShowError on 404",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowError })
    }
}
