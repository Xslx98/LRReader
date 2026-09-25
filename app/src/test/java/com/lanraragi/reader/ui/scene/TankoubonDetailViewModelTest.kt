package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.collectInto
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import com.lanraragi.reader.awaitViewModelIdle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.ui.scene.TankoubonDetailViewModel.TankDetailUiEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * [TankoubonDetailViewModel] reorder contract (spec 2026-09-21 §2/§4):
 * every order change is one PUT of the full order, undoable automatic
 * actions emit the previous order, a no-op sort says so without a PUT,
 * and a failed PUT rolls the members back to server truth.
 *
 * Tank cover contract (spec 2026-09-22-tank-cover §3.2/§4): setCover PUTs
 * the global page of the chosen member page and remembers the choice; a
 * reorder re-applies the remembered cover (twice, best effort) at the page
 * recomputed for the new order; a choice whose member is gone is dropped.
 * Members have 10 pages each, so global page = memberIndex * 10 + page0 + 1.
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

    /** Order full_data is emitted in; null = same as [serverOrder]. */
    @Volatile
    private var fullDataOrder: List<String>? = null

    private val putBodies = CopyOnWriteArrayList<List<String>>()

    /** Every `PUT …/thumbnail?page=N` seen, as N, in arrival order. */
    private val coverPuts = CopyOnWriteArrayList<Int>()

    private class MemoryStorage : TankCoverChoiceStore.Storage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

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
            ctx.getSharedPreferences("tank_detail_vm_test", Context.MODE_PRIVATE)
        )
        LRRAuthManager.setServerUrl(server.url("").toString().removeSuffix("/"))

        val testNetworkModule = stubNetworkModule(client, File(ctx.cacheDir, "test-cache"))
        val testAppModule = stubAppModule(ctx)
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
        val data = (fullDataOrder ?: serverOrder).joinToString(",") { id ->
            """{"arcid":"$id","title":"${titles.getValue(id)}","tags":"","lastreadtime":0,"progress":0,""" +
                """"pagecount":10,"isnew":"false","extension":"zip","filename":"$id.zip","size":1,"summary":""}"""
        }
        val archives = serverOrder.joinToString(",") { "\"$it\"" }
        return """{"result":{"id":"$TANK","name":"Tank","summary":null,"tags":null,"progress":0,""" +
            """"archives":[$archives],"full_data":[$data]},"total":1,"filtered":1}"""
    }

    private fun loadedVm(
        store: TankCoverChoiceStore = TankCoverChoiceStore(MemoryStorage()),
    ): TankoubonDetailViewModel {
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
    fun applyOrder_putsTheFullOrderAndUpdatesMembers() {
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.applyOrder(listOf(ID_EP1, ID_EP2, ID_EXTRA), undoable = false)

        awaitUntil { putBodies.size == 1 }
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), putBodies[0])
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), vm.members.value.map { it.arcid })
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), vm.memberIds)
        awaitViewModelIdle(vm)
        assertTrue("non-undoable reorders emit no OrderApplied", events.none { it is TankDetailUiEvent.OrderApplied })
    }

    @Test
    fun sortByTitle_putsEpisodeOrderAndEmitsPreviousOrderForUndo() {
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.sortByTitle()

        awaitUntil { events.any { it is TankDetailUiEvent.OrderApplied } }
        assertEquals(listOf(ID_EP1, ID_EP2, ID_EXTRA), putBodies.single())
        val undo = events.filterIsInstance<TankDetailUiEvent.OrderApplied>().single()
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), undo.previousOrder)
    }

    @Test
    fun reverseOrder_putsReversedOrder() {
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.reverseOrder()

        awaitUntil { events.any { it is TankDetailUiEvent.OrderApplied } }
        assertEquals(listOf(ID_EP1, ID_EXTRA, ID_EP2), putBodies.single())
    }

    @Test
    fun sortByTitle_whenAlreadySorted_reportsWithoutPut() {
        serverOrder = listOf(ID_EP1, ID_EP2, ID_EXTRA)
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.sortByTitle()

        awaitUntil {
            events.any { it is TankDetailUiEvent.ShowSuccess && it.messageResId == R.string.tank_already_sorted }
        }
        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun applyOrder_failureRollsBackToServerOrderAndReportsError() {
        putStatus = 500
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.sortByTitle()

        awaitUntil { events.any { it is TankDetailUiEvent.ShowError } }
        awaitUntil { vm.members.value.map { it.arcid } == listOf(ID_EP2, ID_EXTRA, ID_EP1) }
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), vm.memberIds)
        assertTrue(events.none { it is TankDetailUiEvent.OrderApplied })
    }

    @Test
    fun applyOrder_failureWithUnreachableServerStillRollsBackLocally() {
        val vm = loadedVm()
        val events = vm.uiEvent.collectInto(eventScope)
        server.shutdown()

        vm.sortByTitle()

        awaitUntil { events.any { it is TankDetailUiEvent.ShowError } }
        awaitUntil { vm.members.value.map { it.arcid } == listOf(ID_EP2, ID_EXTRA, ID_EP1) }
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), vm.memberIds)
    }

    @Test
    fun load_ordersMembersByArchivesNotFullData() {
        // full_data arrives in a different order than `archives`.
        fullDataOrder = listOf(ID_EP1, ID_EP2, ID_EXTRA)
        val vm = loadedVm()
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), vm.members.value.map { it.arcid })
        assertEquals(listOf(ID_EP2, ID_EXTRA, ID_EP1), vm.memberIds)
    }

    // ---- cover ----

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
        const val TANK = "TANK_0000000001"
        val ID_EP1 = "1".padStart(40, '0')
        val ID_EP2 = "2".padStart(40, '0')
        val ID_EXTRA = "3".padStart(40, '0')
    }
}
