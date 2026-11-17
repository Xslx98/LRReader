package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.installActiveServer
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.tankoubon.TankRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [GalleryListViewModel.loadPage]: the list's data path against a
 * MockWebServer LANraragi — dense pages chained to raw server offsets (NET-2),
 * the page bookkeeping handed to the list, superseded loads cancelled
 * (PERF-12), the no-server failure, and tank mode's whole-list page followed
 * by the stand-in covers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryListViewModelLoadTest {

    private lateinit var ctx: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        AppearanceSettings.putGroupTanks(false)
        AppearanceSettings.putHideCompleted(false)
        server = MockWebServer()
        server.start()

        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("list_load_test", Context.MODE_PRIVATE))
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        ServiceRegistry.initializeForTest(
            network = stubNetworkModule(client, File(ctx.cacheDir, "list-load-test")),
            app = stubAppModule(ctx),
        )
        installActiveServer(server.url("").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SecureCredentialStore.clear()
        server.shutdown()
    }

    private val archiveQuery = GalleryListQuery(GalleryListLoader.SearchInputs("artist:foo", null), "title", "asc")

    private fun archiveJson(i: Int): String {
        val arcid = i.toString(16).padStart(40, '0')
        return """{"arcid":"$arcid","title":"Archive $i","tags":"","isnew":"false","extension":"zip",""" +
            """"filename":"$arcid.zip","pagecount":10,"progress":0,"lastreadtime":0}"""
    }

    private fun searchJson(count: Int, total: Int, from: Int = 1): String {
        val data = (from until from + count).joinToString(",") { archiveJson(it) }
        return """{"data":[$data],"draw":1,"recordsFiltered":$total,"recordsTotal":$total}"""
    }

    /** Loads [page] and waits for its first outcome; [accept] is what the list answers. */
    private fun GalleryListViewModel.loadAndAwait(
        query: GalleryListQuery,
        page: Int,
        accept: Boolean = true,
    ): GalleryListLoad {
        val results = CopyOnWriteArrayList<GalleryListLoad>()
        loadPage(query, page) { results += it; accept }
        awaitUntil(message = "page $page never delivered") { results.isNotEmpty() }
        return results.first()
    }

    @Test
    fun `a short server page chains the next page from the rows it returned`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 100)))
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 100, from = 21)))
        val vm = GalleryListViewModel()

        val first = vm.loadAndAwait(archiveQuery, 0) as GalleryListLoad.Page
        assertEquals(20, first.rows.size)
        assertEquals("one more page follows", 2, first.pageCount)
        assertEquals(1, first.nextPage)
        assertEquals(R.string.gallery_list_empty_hit, first.emptyText)
        assertEquals(GalleryListViewModel.SearchParams("artist:foo", null, "title", "asc"), first.issued)
        assertTrue(server.awaitRequest().requestUrl!!.queryParameter("start") in setOf(null, "0"))

        vm.loadAndAwait(archiveQuery, 1)
        assertEquals("20", server.awaitRequest().requestUrl!!.queryParameter("start"))
    }

    @Test
    fun `a page the list no longer wants is not chained`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 100)))
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 100, from = 21)))
        val vm = GalleryListViewModel()

        vm.loadAndAwait(archiveQuery, 0, accept = false)
        server.awaitRequest()
        vm.loadAndAwait(archiveQuery, 1)

        assertEquals("nominal fallback offset", "50", server.awaitRequest().requestUrl!!.queryParameter("start"))
    }

    @Test
    fun `the last page reports no next page`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 10, total = 10)))

        val page = GalleryListViewModel().loadAndAwait(archiveQuery, 0) as GalleryListLoad.Page

        assertEquals(1, page.pageCount)
        assertEquals(0, page.nextPage)
    }

    @Test
    fun `a server error is handed to the list`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val load = GalleryListViewModel().loadAndAwait(archiveQuery, 0)

        assertTrue("expected Failed, got $load", load is GalleryListLoad.Failed)
    }

    @Test
    fun `without a server the load fails at once with its own text`() {
        com.lanraragi.reader.installProfiles()
        val results = mutableListOf<GalleryListLoad>()

        GalleryListViewModel().loadPage(archiveQuery, 0) { results += it; true }

        assertEquals(listOf<GalleryListLoad>(GalleryListLoad.FailedWith(R.string.lrr_no_servers)), results)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a new load cancels the one it supersedes`() {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody(searchJson(count = 1, total = 1))
            }
        }
        val vm = GalleryListViewModel()

        vm.loadPage(archiveQuery, 0) { true }
        val first = vm.currentLoad!!
        vm.loadPage(archiveQuery, 0) { true }
        val second = vm.currentLoad!!

        awaitUntil(message = "superseded load still running") { first.isCancelled }
        assertTrue(second.isActive)
        release.countDown()
        second.cancel()
    }

    @Test
    fun `tank mode hands the whole tank list as one page, then the stand-in covers`() {
        val tankId = "TANK_0000000201"
        val member = 1.toString(16).padStart(40, '0')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // 202: no generated cover yet (the probe also queues one).
                    "thumbnail" in path -> MockResponse().setResponseCode(202)
                    path.startsWith("/api/tankoubons") -> MockResponse().setBody(
                        """{"result":[{"id":"$tankId","name":"Alpha","archives":["$member"],""" +
                            """"summary":null,"tags":null,"progress":0}],"total":1,"filtered":1}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val vm = GalleryListViewModel().apply { tanks = TankRepository({ _, _, _ -> }) }
        val results = CopyOnWriteArrayList<GalleryListLoad>()

        vm.loadPage(GalleryListQuery(GalleryListLoader.SearchInputs(null, null), "title", "asc", tankMode = true), 0) {
            results += it
            true
        }
        awaitUntil(message = "tank page and covers never arrived: $results") { results.size == 2 }

        val page = results[0] as GalleryListLoad.Page
        assertEquals(listOf(tankId), page.rows.map { it.arcid })
        assertEquals(1, page.pageCount)
        assertEquals(0, page.nextPage)
        assertEquals(R.string.tankoubons_empty, page.emptyText)
        assertNull("tank pages never anchor a reading context", page.issued)
        val covers = results[1] as GalleryListLoad.TankCovers
        assertTrue(covers.thumbnailUrls.getValue(tankId).contains(member))
    }

    // ── The list screen state (GalleryListState) ────────────────────────

    @Test
    fun `the first load lands as the list's content and reports its search`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 100)))
        val vm = GalleryListViewModel()
        val landed = CopyOnWriteArrayList<GalleryListViewModel.SearchParams>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { vm.searchLanded.collect { landed += it } }

        vm.loadListIfIdle(archiveQuery)
        awaitUntil(message = "no content: ${vm.listState.value}") { vm.listState.value is GalleryListState.Content }

        assertEquals(20, vm.listState.value.pages.rows.size)
        assertTrue(vm.listState.value.pages.canLoadMore)
        assertEquals("artist:foo", landed.single().filter)
        vm.loadListIfIdle(archiveQuery)
        assertEquals("a second first load is a no-op", 1, server.requestCount)
        scope.cancel()
    }

    @Test
    fun `the next page appends to the list`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 40)))
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 40, from = 21)))
        val vm = GalleryListViewModel()
        vm.loadList(GalleryLoadKind.Initial, archiveQuery)
        awaitUntil { vm.listState.value is GalleryListState.Content }

        vm.loadList(GalleryLoadKind.NextPage, archiveQuery)
        awaitUntil { vm.listState.value.pages.rows.size == 40 }

        assertEquals(listOf(20, 40), vm.listState.value.pages.pageEnds)
        assertFalse(vm.listState.value.pages.canLoadMore)
    }

    @Test
    fun `a failed pull keeps the rows and reports the failure`() {
        server.enqueue(MockResponse().setBody(searchJson(count = 20, total = 20)))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val vm = GalleryListViewModel()
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { vm.listNotice.collect { notices += it } }
        vm.loadList(GalleryLoadKind.Initial, archiveQuery)
        awaitUntil { vm.listState.value is GalleryListState.Content }

        vm.loadList(GalleryLoadKind.Pull, archiveQuery)
        awaitUntil(message = "no notice") { notices.isNotEmpty() }

        assertTrue(vm.listState.value is GalleryListState.Content)
        assertEquals(20, vm.listState.value.pages.rows.size)
        scope.cancel()
    }

    @Test
    fun `without a server the first load is the error at once`() {
        com.lanraragi.reader.installProfiles()
        val vm = GalleryListViewModel()

        vm.loadList(GalleryLoadKind.Initial, archiveQuery)

        assertEquals(
            GalleryListState.Error(ctx.getString(R.string.lrr_no_servers), GalleryPages()),
            vm.listState.value,
        )
    }
}
