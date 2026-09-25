package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.awaitViewModelIdle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.AppProxySelector
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.module.IAppModule
import com.lanraragi.reader.module.INetworkModule
import com.lanraragi.reader.module.NetworkMonitor
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tank cover contract (spec 2026-09-22-tank-cover §3.2/§4): setCover PUTs
 * the global page of the chosen member page and remembers the choice; a
 * reorder re-applies the remembered cover (twice, best effort) at the page
 * recomputed for the new order; a choice whose member is gone is dropped.
 * Members have 10 pages each, so global page = memberIndex * 10 + page0 + 1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankoubonDetailViewModelCoverTest {

    private lateinit var server: MockWebServer
    private lateinit var ctx: Context

    @Volatile
    private var serverOrder: List<String> = listOf(ID_EP2, ID_EXTRA, ID_EP1)

    /** Every `PUT …/thumbnail?page=N` seen, as N, in arrival order. */
    private val coverPuts = CopyOnWriteArrayList<Int>()

    private class MemoryStorage : TankCoverChoiceStore.Storage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "PUT" && path == "/api/tankoubons/$TANK" -> {
                        MockResponse().setBody("""{"success":1}""")
                    }
                    request.method == "PUT" && path.startsWith("/api/tankoubons/$TANK/thumbnail") -> {
                        coverPuts.add(path.substringAfter("page=").toInt())
                        MockResponse().setBody("""{"success":1}""")
                    }
                    path.startsWith("/api/tankoubons/$TANK/full") -> MockResponse().setBody(fullJson())
                    // HEAD (cover probes) must not carry a body, or it corrupts the connection.
                    path.startsWith("/api/tankoubons/$TANK/thumbnail") ->
                        if (request.method == "HEAD") MockResponse() else MockResponse().setBody("x")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        LRRAuthManager.initialize(ctx)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("tank_detail_cover_test", Context.MODE_PRIVATE)
        )
        LRRAuthManager.setServerUrl(server.url("").toString().removeSuffix("/"))

        val testNetworkModule = object : INetworkModule {
            override val cache: Cache get() = Cache(File(ctx.cacheDir, "test-cache"), 1024)
            override val proxySelector: AppProxySelector get() = throw UnsupportedOperationException()
            override val okHttpClient: OkHttpClient = client
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
        ServiceRegistry.initializeForTest(network = testNetworkModule, app = testAppModule)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LRRAuthManager.clear()
        server.shutdown()
    }

    private fun fullJson(): String {
        val data = serverOrder.joinToString(",") { id ->
            """{"arcid":"$id","title":"$id","tags":"","lastreadtime":0,"progress":0,""" +
                """"pagecount":10,"isnew":"false","extension":"zip","filename":"$id.zip","size":1,"summary":""}"""
        }
        val archives = serverOrder.joinToString(",") { "\"$it\"" }
        return """{"result":{"id":"$TANK","name":"Tank","summary":null,"tags":null,"progress":0,""" +
            """"archives":[$archives],"full_data":[$data]},"total":1,"filtered":1}"""
    }

    private fun loadedVm(store: TankCoverChoiceStore): TankoubonDetailViewModel {
        val vm = TankoubonDetailViewModel()
        vm.baseUrlResolver = { LRRAuthManager.getServerUrl()!! }
        vm.coverChoices = store
        vm.coverReapplyDelayMs = 0L
        vm.init(TANK, "Tank", profileId = 5L)
        vm.load()
        awaitUntil { vm.members.value.size == 3 && !vm.isLoading.value }
        return vm
    }

    @Test
    fun setCover_putsTheGlobalPageOfTheChosenMemberPageAndRemembersIt() {
        val store = TankCoverChoiceStore(MemoryStorage())
        val vm = loadedVm(store)

        vm.setCover(memberIndex = 1, page0 = 3)

        awaitUntil { coverPuts.size == 1 }
        assertEquals(listOf(14), coverPuts.toList())
        assertEquals(TankCoverChoiceStore.Choice(ID_EXTRA, 3, 5L), store.get(TANK))
    }

    @Test
    fun applyOrder_reappliesTheRememberedCoverAtTheRecomputedPageTwice() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put(TANK, TankCoverChoiceStore.Choice(ID_EP1, page0 = 2, profileId = 5L))
        val vm = loadedVm(store)

        // EP1 moves from index 2 (global 23) to index 0 (global 3).
        vm.applyOrder(listOf(ID_EP1, ID_EP2, ID_EXTRA), undoable = false)

        awaitUntil { coverPuts.size == 2 }
        assertEquals(listOf(3, 3), coverPuts.toList())
        assertEquals(TankCoverChoiceStore.Choice(ID_EP1, 2, 5L), store.get(TANK))
    }

    @Test
    fun applyOrder_withoutAChoiceNeverTouchesTheCover() {
        val vm = loadedVm(TankCoverChoiceStore(MemoryStorage()))

        vm.applyOrder(listOf(ID_EP1, ID_EP2, ID_EXTRA), undoable = false)

        awaitUntil { vm.memberIds == listOf(ID_EP1, ID_EP2, ID_EXTRA) }
        awaitViewModelIdle(vm)
        assertTrue("no cover PUT expected, got $coverPuts", coverPuts.isEmpty())
    }

    @Test
    fun applyOrder_dropsAChoiceWhoseArchiveIsNoLongerAMember() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put(TANK, TankCoverChoiceStore.Choice("z".repeat(40), page0 = 0, profileId = 5L))
        val vm = loadedVm(store)

        vm.applyOrder(listOf(ID_EP1, ID_EP2, ID_EXTRA), undoable = false)

        awaitUntil { store.get(TANK) == null }
        awaitViewModelIdle(vm)
        assertTrue(coverPuts.isEmpty())
        assertNull(store.get(TANK))
    }

    private companion object {
        const val TANK = "TANK_1688616437"
        val ID_EP1 = "1".repeat(40)
        val ID_EP2 = "2".repeat(40)
        val ID_EXTRA = "3".repeat(40)
    }
}
