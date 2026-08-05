package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkMonitor
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests for [GalleryListViewModel]'s archive upload orchestration: the
 * pre-upload duplicate check (local arcid + existence probe) must short-circuit
 * before any bytes are transferred, and the happy path must upload and report
 * success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryListViewModelUploadTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var server: MockWebServer

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
            ctx.getSharedPreferences("upload_vm_test", Context.MODE_PRIVATE)
        )
        LRRAuthManager.setServerUrl(server.url("").toString().removeSuffix("/"))
        LRRClientProvider.init(ctx)

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()

        val testNetworkModule = object : INetworkModule {
            override val cache: Cache get() = Cache(File(ctx.cacheDir, "upload-test-cache"), 1024)
            override val proxySelector: EhProxySelector get() = throw UnsupportedOperationException()
            override val okHttpClient: OkHttpClient = client
            override val longReadClient: OkHttpClient = client
            override val uploadClient: OkHttpClient = client
            // retryOnFailure reads this via runCatching{}.getOrDefault(false), so
            // throwing here is treated as "online" — exactly what we want in tests.
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
            override val searchHistoryRepository get() = throw NotImplementedError("Not needed for these tests")
            override val profileRepository get() = throw NotImplementedError("not needed")
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LRRAuthManager.clear()
        db.close()
        server.shutdown()
    }

    private fun requestOf(bytes: ByteArray) = GalleryListViewModel.UploadRequest(
        displayName = "test.zip",
        cacheDir = ctx.cacheDir,
        openStream = { ByteArrayInputStream(bytes) }
    )

    private fun awaitTerminal(vm: GalleryListViewModel, timeoutMs: Long = 5000): GalleryListViewModel.UploadUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = vm.uploadState.value
            if (s is GalleryListViewModel.UploadUiState.DuplicateSkipped ||
                s is GalleryListViewModel.UploadUiState.Success ||
                s is GalleryListViewModel.UploadUiState.Failed
            ) {
                return s
            }
            Thread.sleep(25)
        }
        return vm.uploadState.value
    }

    @Test
    fun uploadArchive_whenDuplicate_skipsUploadWithoutTransferring() {
        // Existence probe finds the archive (HTTP 200 metadata) → must short-circuit.
        server.enqueue(
            MockResponse().setBody(
                """{"arcid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"T","tags":"",
                   "isnew":"false","extension":"zip","filename":"t.zip","pagecount":1,
                   "progress":0,"lastreadtime":0}"""
            )
        )

        val vm = GalleryListViewModel()
        vm.uploadArchive(requestOf(ByteArray(100) { it.toByte() }))

        val terminal = awaitTerminal(vm)
        assertTrue(
            "expected DuplicateSkipped, was $terminal",
            terminal is GalleryListViewModel.UploadUiState.DuplicateSkipped
        )
        // Only the metadata probe was sent — no upload PUT was attempted.
        assertEquals(1, server.requestCount)
        val probe = server.takeRequest()
        assertEquals("GET", probe.method)
        assertTrue("probe should hit /metadata, was ${probe.path}", probe.path!!.endsWith("/metadata"))
    }

    @Test
    fun uploadArchive_whenNotDuplicate_uploadsAndSucceeds() {
        // Probe says unknown id (HTTP 400 — LANraragi's not-found), then upload succeeds.
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"success":0,"error":"This ID does not exist"}""")
        )
        server.enqueue(MockResponse().setBody("""{"success":1,"id":"newarcid"}"""))

        val vm = GalleryListViewModel()
        vm.uploadArchive(requestOf(ByteArray(100) { it.toByte() }))

        val terminal = awaitTerminal(vm)
        assertTrue(
            "expected Success, was $terminal",
            terminal is GalleryListViewModel.UploadUiState.Success
        )
        assertEquals(
            "newarcid",
            (terminal as GalleryListViewModel.UploadUiState.Success).arcid
        )
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.endsWith("/metadata"))
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/api/archives/upload", upload.path)
    }
}
