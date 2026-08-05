package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [GalleryListViewModel]'s multi-select batch executor: per-item
 * fault isolation, aggregate result reporting, ArchiveDeletedEvent fan-out on
 * delete, and the bounded-concurrency cap.
 *
 * Uses a path-based MockWebServer [Dispatcher] (arcid in path) rather than
 * enqueue order because batch items run concurrently and completion order is
 * nondeterministic. The batch download path (runBatchDownload) is NOT covered
 * here: it is a trivial main-thread enqueue loop, and [com.hippo.ehviewer.download.DownloadManager]
 * is a final class that can only be exercised with the full Room + Settings +
 * IDataModule scaffolding of DownloadsViewModelTest — whose init-time network
 * sync would also pollute this class's MockWebServer request accounting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryListViewModelBatchTest {

    private lateinit var ctx: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()

        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("batch_vm_test", Context.MODE_PRIVATE)
        )
        LRRAuthManager.setServerUrl(server.url("").toString().removeSuffix("/"))
        LRRClientProvider.init(ctx)

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
        val testNetworkModule = object : INetworkModule {
            override val cache: Cache get() = Cache(File(ctx.cacheDir, "batch-test-cache"), 1024)
            override val proxySelector: EhProxySelector get() = throw UnsupportedOperationException()
            override val okHttpClient: OkHttpClient = client
            override val longReadClient: OkHttpClient = client
            override val uploadClient: OkHttpClient = client
            override val networkMonitor: NetworkMonitor get() = throw UnsupportedOperationException()
        }
        ServiceRegistry.initializeForTest(network = testNetworkModule)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LRRAuthManager.clear()
        server.shutdown()
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun mockBaseUrl() = server.url("").toString().removeSuffix("/")

    /** VM with the base-URL resolution pinned to the MockWebServer (no profile registry needed). */
    private fun newVm(): GalleryListViewModel = GalleryListViewModel().also { vm ->
        vm.baseUrlResolver = { mockBaseUrl() }
    }

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun archiveOf(arcid: String) = Archive(
        arcid = arcid,
        title = "Title-${arcid.take(4)}",
        tags = emptyMap(),
        pagecount = 1,
        progress = 0,
        extension = "zip",
        filename = "t.zip",
        thumbnailUrl = "",
        rating = 0f,
        isnew = true,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = 0L,
    )

    /**
     * Subscribe to [GalleryListViewModel.batchResultEvent] BEFORE triggering the
     * batch (the flow has no replay), then await the one-shot result.
     *
     * The collector runs on [Dispatchers.Default] so that [withTimeout] uses
     * real time — on the test scheduler the 10s would elapse in virtual time
     * the moment the scheduler idles, while the real OkHttp/MockWebServer IO
     * is still in flight. UNDISPATCHED start guarantees the subscription is
     * registered before [trigger] runs.
     */
    private suspend fun TestScope.awaitBatchResult(
        vm: GalleryListViewModel,
        trigger: () -> Unit,
    ): GalleryListViewModel.BatchResult {
        val deferred = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(RESULT_TIMEOUT_MS) { vm.batchResultEvent.first() }
        }
        trigger()
        return deferred.await()
    }

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    fun `runBatch stamps the initiating owner token on the emitted result`() = runTest {
        // The result must carry the token of the scene that started the batch
        // so that, with an activity-scoped ViewModel shared across stacked list
        // instances, only the initiating scene handles/buffers it instead of
        // every instance fanning out a duplicate dialog.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse(200, """{"success":1}""")
        }
        val vm = newVm()
        val token = Any()
        val result = awaitBatchResult(vm) {
            vm.runBatch(GalleryListViewModel.BatchOp.ClearNew, listOf(archiveOf(ARC_A)), owner = token)
        }
        assertSame("result must carry the initiating owner token", token, result.owner)
    }

    @Test
    fun `clearNew batch aggregates per-item results and isolates failures`() = runTest {
        // 200 for arcids A and C, 500 for B — path-based dispatch because the
        // batch runs concurrently and request order is nondeterministic.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (ARC_B in path) {
                    jsonResponse(500, """{"success":0,"error":"boom"}""")
                } else {
                    jsonResponse(200, """{"success":1}""")
                }
            }
        }

        val vm = newVm()
        val result = awaitBatchResult(vm) {
            vm.runBatch(
                GalleryListViewModel.BatchOp.ClearNew,
                listOf(archiveOf(ARC_A), archiveOf(ARC_B), archiveOf(ARC_C)),
            )
        }

        assertEquals(GalleryListViewModel.BatchOp.ClearNew, result.op)
        assertEquals(listOf(ARC_A, ARC_C), result.succeeded)
        assertEquals(1, result.failed.size)
        assertEquals(ARC_B, result.failed[0].arcid)
        assertNull("batchProgress should be back to idle", vm.batchProgress.value)
    }

    @Test
    fun `delete batch posts ArchiveDeletedEvent per success and reports server-rejected items as failures`() =
        runTest {
            // A → deleted; B → HTTP 200 but success:0 (server-side rejection,
            // e.g. file locked) which deleteArchive maps to an IOException.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (ARC_B in path) {
                        jsonResponse(200, """{"success":0,"error":"locked"}""")
                    } else {
                        jsonResponse(200, """{"success":1,"filename":"x"}""")
                    }
                }
            }

            val deletedEvents = CopyOnWriteArrayList<String>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                AppEventBus.archiveDeletedEvent.collect { deletedEvents += it.arcid }
            }

            val vm = newVm()
            val result = awaitBatchResult(vm) {
                vm.runBatch(
                    GalleryListViewModel.BatchOp.DeleteArchives,
                    listOf(archiveOf(ARC_A), archiveOf(ARC_B)),
                )
            }
            advanceUntilIdle() // drain pending collector resumptions before asserting
            collectJob.cancel()

            assertEquals("exactly one delete event, for A", listOf(ARC_A), deletedEvents)
            assertEquals(listOf(ARC_A), result.succeeded)
            assertEquals(1, result.failed.size)
            assertEquals(ARC_B, result.failed[0].arcid)
            assertTrue(
                "failure reason should carry the server error, was '${result.failed[0].reason}'",
                result.failed[0].reason.contains("locked"),
            )
        }

    @Test
    fun `batch respects concurrency cap`() = runTest {
        val inFlight = AtomicInteger(0)
        val maxParallel = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = inFlight.incrementAndGet()
                maxParallel.updateAndGet { maxOf(it, now) }
                try {
                    // Hold each request long enough for the others to pile up.
                    Thread.sleep(50)
                } finally {
                    inFlight.decrementAndGet()
                }
                return jsonResponse(200, """{"success":1}""")
            }
        }

        val archives = (0 until 12).map { archiveOf(it.toString().padStart(40, '0')) }
        val vm = newVm()
        val result = awaitBatchResult(vm) {
            vm.runBatch(GalleryListViewModel.BatchOp.ClearNew, archives)
        }

        assertEquals(12, result.succeeded.size)
        assertTrue("no failures expected, got ${result.failed}", result.failed.isEmpty())
        assertTrue(
            "observed parallelism ${maxParallel.get()} exceeds the cap of 4",
            maxParallel.get() <= 4,
        )
    }

    private companion object {
        val ARC_A = "a".repeat(40)
        val ARC_B = "b".repeat(40)
        val ARC_C = "c".repeat(40)
        const val RESULT_TIMEOUT_MS = 10_000L
    }
}
