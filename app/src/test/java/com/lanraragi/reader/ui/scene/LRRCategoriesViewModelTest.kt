package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.installActiveServer
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubDataModule
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.collectInto
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.domain.Category
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * The ViewModel dispatches work to `Dispatchers.IO`. Tests use [awaitUntil]
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

        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("categories_vm_test", Context.MODE_PRIVATE)
        )

        val testNetworkModule = stubNetworkModule(client, File(ctx.cacheDir, "test-cache"))

        val testAppModule = stubAppModule(ctx)

        // The data module too: left out, it would be whatever an earlier test
        // class installed, and a fake there that throws an Error from
        // quickSearchRepository (read by the default category-name sync after
        // every successful load) escapes the view model's catch and leaves
        // the state Loading for good.
        ServiceRegistry.initializeForTest(
            network = testNetworkModule,
            data = stubDataModule(),
            app = testAppModule
        )
        installActiveServer(server.url("").toString().removeSuffix("/"))

        // Use a real dispatcher scope for event collection (not test scope)
        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        SecureCredentialStore.clear()
        server.shutdown()
    }

    // ── loadCategories ─────────────────────────────────────────────

    @Test
    fun loadCategories_success_populatesStateFlow() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_aaaaaaaaaa","name":"Favorites","archives":["a1"],"pinned":"1","search":""},
            {"id":"c2","name":"Dynamic","archives":[],"pinned":"0","search":"artist:foo"}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitUntil { vm.state.value.items.size == 2 }
        assertEquals("Favorites", vm.state.value.items[0].name)
        assertEquals("Dynamic", vm.state.value.items[1].name)
    }

    @Test
    fun loadCategories_success_syncsQuickSearchCategoryNames() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_aaaaaaaaaa","name":"Favorites","archives":["a1"],"pinned":"1","search":""},
            {"id":"c2","name":"Dynamic","archives":[],"pinned":"0","search":"artist:foo"}
        ]"""))
        val synced = CopyOnWriteArrayList<List<Category>>()

        val vm = LRRCategoriesViewModel(categoryNameSync = { synced.add(it) })
        vm.loadCategories()

        awaitUntil { synced.isNotEmpty() }
        assertEquals(listOf("SET_aaaaaaaaaa", "c2"), synced.single().map { it.id })
    }

    @Test
    fun loadCategories_pinnedSortFirst() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_aaaaaaaaaa","name":"Unpinned","archives":[],"pinned":"0","search":""},
            {"id":"c2","name":"Pinned","archives":[],"pinned":"1","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitUntil { vm.state.value.items.size == 2 }
        assertTrue("First item should be pinned", vm.state.value.items[0].isPinned())
        assertFalse("Second item should not be pinned", vm.state.value.items[1].isPinned())
        assertEquals("Pinned", vm.state.value.items[0].name)
    }

    @Test
    fun loadCategories_skipsNamelessEntries() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_aaaaaaaaaa","name":"Valid","archives":[],"pinned":"0","search":""},
            {"id":"c2","name":"","archives":[],"pinned":"0","search":""},
            {"id":"c3","name":null,"archives":[],"pinned":"0","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitUntil { vm.state.value !is ListScreenState.Loading }
        assertEquals("Should skip empty/null names", 1, vm.state.value.items.size)
        assertEquals("Valid", vm.state.value.items[0].name)
    }

    @Test
    fun loadCategories_startsLoading_andAnEmptyAnswerIsEmpty() {
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        assertEquals(ListScreenState.Loading, vm.state.value)

        vm.loadCategories()
        awaitUntil { vm.state.value !is ListScreenState.Loading }

        assertEquals(ListScreenState.Empty, vm.state.value)
    }

    /** A first load that fails is the screen's error state (kept across rotation), not a toast. */
    @Test
    fun loadCategories_error_isTheStateWithNothingRetained() {
        // Use 401 (4xx) so retryOnFailure fast-fails without retrying
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val vm = LRRCategoriesViewModel()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.loadCategories()

        awaitUntil { vm.state.value !is ListScreenState.Loading }
        val state = vm.state.value
        assertTrue("error state: $state", state is ListScreenState.Error && state.retained.isEmpty())
        assertTrue("no toast for an error the screen shows", events.isEmpty())
    }

    /** A failed reload while categories are shown keeps them and reports the failure as a toast. */
    @Test
    fun loadCategories_failedReload_keepsTheCategories_andToasts() {
        server.enqueue(MockResponse().setBody(
            """[{"id":"SET_aaaaaaaaaa","name":"Kept","archives":[],"pinned":"0","search":""}]"""
        ))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val vm = LRRCategoriesViewModel()
        val events = vm.uiEvent.collectInto(eventScope)
        vm.loadCategories()
        awaitUntil { vm.state.value is ListScreenState.Content }

        vm.loadCategories()
        assertTrue("a reload keeps showing them", vm.state.value is ListScreenState.Content)
        awaitUntil { vm.state.value is ListScreenState.Error }

        assertEquals(listOf("Kept"), vm.state.value.items.map { it.name })
        awaitUntil { events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowError } }
    }

    @Test
    fun loadCategories_emptyList_setsEmptyCategories() {
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        vm.loadCategories()

        awaitUntil { vm.state.value !is ListScreenState.Loading }
        assertTrue("Categories should be empty", vm.state.value.items.isEmpty())
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
        val events = vm.uiEvent.collectInto(eventScope)

        vm.createCategory("NewCat", null, false)

        awaitUntil { vm.state.value.items.isNotEmpty() }
        // The ShowSuccess event is delivered to the collector asynchronously
        // (on eventScope), so the categories reload completing does not imply
        // the event has landed in [events] yet — poll the event itself too.
        awaitUntil { events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess } }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
        assertEquals(1, vm.state.value.items.size)
        assertEquals("NewCat", vm.state.value.items[0].name)
    }

    // ── editCategory ─────────────────────────────────────────────

    @Test
    fun editCategory_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_aaaaaaaaaa","name":"Edited","archives":[],"pinned":"1","search":""}
        ]"""))

        val vm = LRRCategoriesViewModel()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.editCategory("SET_aaaaaaaaaa", "Edited", null, true)

        awaitUntil { vm.state.value.items.isNotEmpty() }
        // Same async event delivery as in the createCategory test above.
        awaitUntil { events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess } }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
        assertEquals("Edited", vm.state.value.items[0].name)
    }

    // ── deleteCategory ─────────────────────────────────────────────

    @Test
    fun deleteCategory_success_emitsShowSuccessAndReloads() {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))
        server.enqueue(MockResponse().setBody("[]"))

        val vm = LRRCategoriesViewModel()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.deleteCategory("SET_aaaaaaaaaa")

        awaitUntil { events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess } }
        assertTrue("Should emit ShowSuccess",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowSuccess })
    }

    @Test
    fun deleteCategory_error_emitsShowError() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val vm = LRRCategoriesViewModel()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.deleteCategory("nonexistent")

        awaitUntil { events.isNotEmpty() }
        assertTrue("Should emit ShowError on 404",
            events.any { it is LRRCategoriesViewModel.CategoriesUiEvent.ShowError })
    }
}
