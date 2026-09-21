package com.lanraragi.reader.ui.scene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.AppProxySelector
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.module.IAppModule
import com.lanraragi.reader.module.INetworkModule
import com.lanraragi.reader.module.NetworkMonitor
import com.lanraragi.reader.ui.scene.TankoubonDetailViewModel.TankDetailUiEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TankoubonDetailViewModel] reorder contract (spec 2026-09-21 §2/§4):
 * every order change is one PUT of the full order, undoable automatic
 * actions emit the previous order, a no-op sort says so without a PUT,
 * and a failed PUT rolls the members back to server truth.
 *
 * The mock server keeps a mutable member order so the rollback reload
 * returns whatever the server currently holds. Harness mirrors
 * [TankoubonsViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankoubonDetailViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var ctx: Context
    private lateinit var eventScope: CoroutineScope

    /** Server-side member order; PUTs update it unless [putStatus] is not 200. */
    @Volatile
    private var serverOrder: List<String> = listOf(ID_EP2, ID_EXTRA, ID_EP1)

    @Volatile
    private var putStatus = 200

    private val putBodies = CopyOnWriteArrayList<List<String>>()

    private val titles = mapOf(ID_EP1 to "第1话", ID_EP2 to "第2话", ID_EXTRA to "番外")

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
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val ids = body.getValue("archives").jsonArray.map { it.jsonPrimitive.content }
                        putBodies.add(ids)
                        if (putStatus == 200) serverOrder = ids
                        MockResponse().setResponseCode(putStatus).setBody("""{"success":1}""")
                    }
                    path.startsWith("/api/tankoubons/$TANK/full") -> MockResponse().setBody(fullJson())
                    path.startsWith("/api/tankoubons/$TANK/thumbnail") -> MockResponse().setBody("x")
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
            ctx.getSharedPreferences("tank_detail_vm_test", Context.MODE_PRIVATE)
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
        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        LRRAuthManager.clear()
        server.shutdown()
    }

    private fun fullJson(): String {
        val data = serverOrder.joinToString(",") { id ->
            """{"arcid":"$id","title":"${titles.getValue(id)}","tags":"","lastreadtime":0,"progress":0,""" +
                """"pagecount":10,"isnew":"false","extension":"zip","filename":"$id.zip","size":1,"summary":""}"""
        }
        val archives = serverOrder.joinToString(",") { "\"$it\"" }
        return """{"result":{"id":"$TANK","name":"Tank","summary":null,"tags":null,"progress":0,""" +
            """"archives":[$archives],"full_data":[$data]},"total":1,"filtered":1}"""
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun collectEvents(vm: TankoubonDetailViewModel): CopyOnWriteArrayList<TankDetailUiEvent> {
        val events = CopyOnWriteArrayList<TankDetailUiEvent>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            vm.uiEvent.onSubscription { subscribed.complete(Unit) }.collect { events.add(it) }
        }
        runBlocking { subscribed.await() }
        return events
    }

    private fun loadedVm(): TankoubonDetailViewModel {
        val vm = TankoubonDetailViewModel()
        vm.baseUrlResolver = { LRRAuthManager.getServerUrl()!! }
        vm.init(TANK, "Tank", profileId = 0L)
        vm.load()
        awaitCondition { vm.members.value.size == 3 && !vm.isLoading.value }
        return vm
    }

    @Test
    fun applyOrder_putsTheFullOrderAndUpdatesMembers() {
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.applyOrder(listOf(ID_EP1, ID_EP2, ID_EXTRA), undoable = false)

        awaitCondition { putBodies.size == 1 }
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), putBodies[0])
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), vm.members.value.map { it.arcid })
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), vm.memberIds)
        Thread.sleep(100)
        assertTrue("non-undoable reorders emit no OrderApplied", events.none { it is TankDetailUiEvent.OrderApplied })
    }

    @Test
    fun sortByTitle_putsEpisodeOrderAndEmitsPreviousOrderForUndo() {
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.sortByTitle()

        awaitCondition { events.any { it is TankDetailUiEvent.OrderApplied } }
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), putBodies.single())
        val undo = events.filterIsInstance<TankDetailUiEvent.OrderApplied>().single()
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), undo.previousOrder)
    }

    @Test
    fun reverseOrder_putsReversedOrder() {
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.reverseOrder()

        awaitCondition { events.any { it is TankDetailUiEvent.OrderApplied } }
        assertEquals(listOf(ID_EP1, ID_EXTRA, ID_EP2), putBodies.single())
    }

    @Test
    fun sortByTitle_whenAlreadySorted_reportsWithoutPut() {
        serverOrder = listOf(ID_EP1, ID_EP2, ID_EXTRA)
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.sortByTitle()

        awaitCondition {
            events.any { it is TankDetailUiEvent.ShowSuccess && it.messageResId == R.string.tank_already_sorted }
        }
        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun applyOrder_failureRollsBackToServerOrderAndReportsError() {
        putStatus = 500
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.sortByTitle()

        awaitCondition { events.any { it is TankDetailUiEvent.ShowError } }
        awaitCondition { vm.members.value.map { it.arcid } == listOf(ID_EP2, ID_EXTRA, ID_EP1) }
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), vm.memberIds)
        assertTrue(events.none { it is TankDetailUiEvent.OrderApplied })
    }

    private companion object {
        const val TANK = "TANK_0000000001"
        val ID_EP1 = "1".padStart(40, '0')
        val ID_EP2 = "2".padStart(40, '0')
        val ID_EXTRA = "3".padStart(40, '0')
    }
}
