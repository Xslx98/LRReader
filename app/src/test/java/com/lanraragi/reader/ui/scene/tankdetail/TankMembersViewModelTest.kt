package com.lanraragi.reader.ui.scene.tankdetail

import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.collectInto
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import com.lanraragi.reader.awaitViewModelIdle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.scene.tankdetail.TankMembersViewModel.TankDetailUiEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TankMembersViewModel] reorder contract (spec 2026-09-21 §2/§4):
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
 * returns whatever the server currently holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankMembersViewModelTest {

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

    /** `metadata` of every PUT /api/tankoubons/{id} without `archives` (rename / edit meta). */
    private val metaPuts = CopyOnWriteArrayList<Map<String, String>>()

    /** Every non-HEAD request as "METHOD path", interleaved with the recorded seam calls. */
    private val log = CopyOnWriteArrayList<String>()

    @Volatile
    private var fullStatus = 200

    @Volatile
    private var deleteStatus = 200

    /** HEAD …/thumbnail status: 200 = a generated cover exists, 202 = none yet. */
    @Volatile
    private var thumbStatus = 200

    @Volatile
    private var tankSummary: String? = null

    @Volatile
    private var tankTags: String? = null

    /** Every `PUT …/thumbnail?page=N` seen, as N, in arrival order. */
    private val coverPuts = CopyOnWriteArrayList<Int>()

    private class MemoryStorage(private val onWrite: () -> Unit = {}) : TankCoverChoiceStore.Storage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) {
            this.value = value
            onWrite()
        }
    }

    private val titles = mapOf(ID_EP1 to "第1话", ID_EP2 to "第2话", ID_EXTRA to "番外", ID_LOOSE to "loose")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        TankoubonSupportGate.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method != "HEAD") log.add("${request.method} $path")
                return when {
                    request.method == "PUT" && path == "/api/tankoubons/$TANK" -> {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val archives = body["archives"]
                        if (archives == null) {
                            metaPuts.add(body.getValue("metadata").jsonObject.mapValues { it.value.jsonPrimitive.content })
                            return MockResponse().setResponseCode(putStatus).setBody("""{"success":1}""")
                        }
                        val ids = archives.jsonArray.map { it.jsonPrimitive.content }
                        putBodies.add(ids)
                        if (putStatus == 200) serverOrder = ids
                        MockResponse().setResponseCode(putStatus).setBody("""{"success":1}""")
                    }
                    request.method == "DELETE" && path.startsWith("/api/tankoubons/$TANK") ->
                        MockResponse().setResponseCode(deleteStatus).setBody("""{"success":1}""")
                    request.method == "PUT" && path.startsWith("/api/tankoubons/$TANK/thumbnail") -> {
                        coverPuts.add(path.substringAfter("page=").toInt())
                        MockResponse().setBody("""{"success":1}""")
                    }
                    path.startsWith("/api/tankoubons/$TANK/full") ->
                        if (fullStatus == 200) MockResponse().setBody(fullJson()) else MockResponse().setResponseCode(fullStatus)
                    // HEAD (cover probes) must not carry a body, or it corrupts the connection.
                    path.startsWith("/api/tankoubons/$TANK/thumbnail") ->
                        if (request.method == "HEAD") MockResponse().setResponseCode(thumbStatus) else MockResponse().setBody("x")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("tank_detail_vm_test", Context.MODE_PRIVATE)
        )
        ServerSession.setServerUrl(server.url("").toString().removeSuffix("/"))

        val testNetworkModule = stubNetworkModule(client, File(ctx.cacheDir, "test-cache"))
        val testAppModule = stubAppModule(ctx)
        ServiceRegistry.initializeForTest(network = testNetworkModule, app = testAppModule)
        eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        Dispatchers.resetMain()
        SecureCredentialStore.clear()
        TankoubonSupportGate.clear()
        server.shutdown()
    }

    private fun fullJson(): String {
        val data = (fullDataOrder ?: serverOrder).joinToString(",") { id ->
            """{"arcid":"$id","title":"${titles.getValue(id)}","tags":"","lastreadtime":0,"progress":0,""" +
                """"pagecount":10,"isnew":"false","extension":"zip","filename":"$id.zip","size":1,"summary":""}"""
        }
        val archives = serverOrder.joinToString(",") { "\"$it\"" }
        val summary = tankSummary?.let { "\"$it\"" } ?: "null"
        val tags = tankTags?.let { "\"$it\"" } ?: "null"
        return """{"result":{"id":"$TANK","name":"Tank","summary":$summary,"tags":$tags,"progress":7,""" +
            """"archives":[$archives],"full_data":[$data]},"total":1,"filtered":1}"""
    }

    private fun loadedVm(
        store: TankCoverChoiceStore = TankCoverChoiceStore(MemoryStorage()),
    ): TankMembersViewModel {
        val vm = TankMembersViewModel()
        vm.baseUrlResolver = { ServerSession.getServerUrl()!! }
        vm.coverChoices = store
        vm.coverReapplyDelayMs = 0L
        vm.membershipSync = TankMembershipSyncFactory.Runner { _, _, _ -> }
        vm.untagDownloadMember = { _, arcid -> log.add("untag $arcid") }
        vm.dissolveDownloadGroup = { id -> log.add("dissolve group $id") }
        vm.tagSyncAfterRemove = { _, _, snap, removed -> log.add("tags ${snap.tankId} $removed"); true }
        vm.categorySyncAfterRemove = { _, _, snap, removed -> log.add("categories ${snap.tankId} $removed"); true }
        vm.categoryDissolve = { _, _, id, name -> log.add("dissolve categories $id $name"); true }
        vm.init(TANK, "Tank", profileId = 5L)
        vm.load()
        awaitUntil { vm.members.value.size == 3 && !vm.isLoading.value }
        return vm
    }

    /** The VM after its first load, with the request [log] cleared. */
    private fun quietVm(store: TankCoverChoiceStore = TankCoverChoiceStore(MemoryStorage())): TankMembersViewModel {
        val vm = loadedVm(store)
        awaitViewModelIdle(vm)
        log.clear()
        return vm
    }

    private fun fullGets() = log.count { it.startsWith("GET /api/tankoubons/$TANK/full") }

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

        // The mock logs the PUT on receipt; the choice is stored only after
        // the response returns, so wait for that final effect.
        awaitUntil { store.get(TANK) != null }
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

    // ---- load ----

    @Test
    fun load_publishesServerTruthAndAppendsMembersMissingFromArchives() {
        tankSummary = "s"
        tankTags = "artist:x"
        serverOrder = listOf(ID_EP2, ID_EP1)
        // ID_LOOSE is in full_data but not in `archives`: appended at the end.
        fullDataOrder = listOf(ID_EP1, ID_LOOSE, ID_EP2)
        val vm = loadedVm()

        assertEquals(listOf(ID_EP2, ID_EP1, ID_LOOSE), vm.members.value.map { it.arcid })
        assertEquals(listOf(ID_EP2, ID_EP1, ID_LOOSE), vm.memberIds)
        assertEquals(listOf(0, 10, 20, 30), vm.pageOffsets)
        assertTrue(vm.members.value.all { it.serverProfileId == 5L })
        assertEquals("Tank", vm.tankName.value)
        assertEquals(7, vm.progress.value)
        assertEquals("s", vm.summary)
        assertEquals("artist:x", vm.tags)
        assertTrue(vm.metaLoaded)
        assertNull("a generated cover exists", vm.coverFallbackMember)
        assertEquals(TankoubonSupportGate.Support.SUPPORTED, TankoubonSupportGate.support(vm.baseUrl!!))
    }

    @Test
    fun load_withoutAGeneratedCover_standsInWithTheFirstMember() {
        thumbStatus = 202
        val vm = loadedVm()

        assertEquals(ID_EP2, vm.coverFallbackMember?.arcid)
    }

    @Test
    fun load_404_marksTheServerUnsupported() {
        fullStatus = 404
        val vm = TankMembersViewModel()
        vm.baseUrlResolver = { ServerSession.getServerUrl()!! }
        val events = vm.uiEvent.collectInto(eventScope)
        vm.init(TANK, "Tank", profileId = 5L)

        vm.load()

        awaitUntil { events.isNotEmpty() }
        assertEquals(TankDetailUiEvent.ShowUnsupported, events.single())
        assertFalse(vm.isLoading.value)
        assertFalse(vm.metaLoaded)
        assertTrue(TankoubonSupportGate.isUnsupported(vm.baseUrl!!))
    }

    @Test
    fun load_otherFailure_showsAnError() {
        fullStatus = 500
        val vm = TankMembersViewModel()
        vm.baseUrlResolver = { ServerSession.getServerUrl()!! }
        val events = vm.uiEvent.collectInto(eventScope)
        vm.init(TANK, "Tank", profileId = 5L)

        vm.load()

        awaitUntil { events.isNotEmpty() }
        assertTrue(events.single() is TankDetailUiEvent.ShowError)
        assertFalse(TankoubonSupportGate.isUnsupported(vm.baseUrl!!))
        assertTrue(vm.members.value.isEmpty())
    }

    // ---- rename / edit meta ----

    @Test
    fun rename_putsOnlyTheNameThenReportsAndReloads() {
        val vm = quietVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.rename("New name")

        awaitUntil { fullGets() == 1 }
        assertEquals(listOf(mapOf("name" to "New name")), metaPuts.toList())
        assertTrue("membership untouched", putBodies.isEmpty())
        assertEquals(listOf(TankDetailUiEvent.ShowSuccess(R.string.tank_op_done)), events.toList())
    }

    @Test
    fun editMeta_putsSummaryAndTagsButNeverTheMemberList() {
        val vm = quietVm()

        vm.editMeta("sum", "artist:y")

        awaitUntil { fullGets() == 1 }
        assertEquals(listOf(mapOf("summary" to "sum", "tags" to "artist:y")), metaPuts.toList())
        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun editMeta_failureReportsWithoutReloading() {
        val vm = quietVm()
        val events = vm.uiEvent.collectInto(eventScope)
        putStatus = 423

        vm.editMeta("sum", "artist:y")

        awaitUntil { events.isNotEmpty() }
        assertEquals(TankDetailUiEvent.ShowError(ctx.getString(R.string.tank_locked)), events.single())
        awaitViewModelIdle(vm)
        assertEquals(0, fullGets())
    }

    // ---- remove members ----

    @Test
    fun removeMembers_snapshotsThenDeletesAndUntagsEachThenSyncsTagsAndCategories() {
        val vm = quietVm()
        val events = vm.uiEvent.collectInto(eventScope)

        vm.removeMembers(listOf(ID_EXTRA, ID_EP1))

        awaitUntil { fullGets() == 2 }
        assertEquals(
            listOf(
                "GET /api/tankoubons/$TANK/full?page=-1", // rule-3 snapshot before any removal
                "DELETE /api/tankoubons/$TANK/$ID_EXTRA",
                "untag $ID_EXTRA",
                "DELETE /api/tankoubons/$TANK/$ID_EP1",
                "untag $ID_EP1",
                "tags $TANK [$ID_EXTRA, $ID_EP1]",
                "categories $TANK [$ID_EXTRA, $ID_EP1]",
                "GET /api/tankoubons/$TANK/full?page=-1", // reload
            ),
            log.toList(),
        )
        assertEquals(listOf(TankDetailUiEvent.ShowSuccess(R.string.tank_op_done)), events.toList())
    }

    @Test
    fun removeMembers_ofTheFirstMember_reappliesTheRememberedCoverForTheRemainingOrder() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put(TANK, TankCoverChoiceStore.Choice(ID_EXTRA, page0 = 1, profileId = 5L))
        val vm = quietVm(store)

        vm.removeMembers(listOf(ID_EP2))

        // Remaining order EXTRA, EP1: EXTRA's page 1 (0-indexed) is global page 2.
        awaitUntil { coverPuts.size == 2 }
        assertEquals(listOf(2, 2), coverPuts.toList())
    }

    @Test
    fun removeMembers_ofAnotherMember_dropsAChoiceOnTheRemovedArchiveWithoutACoverPut() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put(TANK, TankCoverChoiceStore.Choice(ID_EP1, page0 = 0, profileId = 5L))
        val vm = quietVm(store)

        vm.removeMembers(listOf(ID_EP1))

        awaitUntil { fullGets() == 2 }
        awaitViewModelIdle(vm)
        assertNull(store.get(TANK))
        assertTrue(coverPuts.isEmpty())
    }

    @Test
    fun removeMembers_lockedTank_stopsAtTheFailedDeleteAndReports() {
        val vm = quietVm()
        val events = vm.uiEvent.collectInto(eventScope)
        deleteStatus = 423

        vm.removeMembers(listOf(ID_EXTRA, ID_EP1))

        awaitUntil { events.isNotEmpty() }
        assertEquals(TankDetailUiEvent.ShowError(ctx.getString(R.string.tank_locked)), events.single())
        awaitViewModelIdle(vm)
        assertEquals(
            listOf("GET /api/tankoubons/$TANK/full?page=-1", "DELETE /api/tankoubons/$TANK/$ID_EXTRA"),
            log.toList(),
        )
    }

    // ---- delete ----

    @Test
    fun deleteTank_clearsCategoriesThenDeletesThenForgetsTheCoverThenDissolvesTheGroup() {
        val store = TankCoverChoiceStore(MemoryStorage { log.add("cover choice written") })
        store.put(TANK, TankCoverChoiceStore.Choice(ID_EP1, page0 = 0, profileId = 5L))
        val vm = quietVm(store)
        val events = vm.uiEvent.collectInto(eventScope)

        vm.deleteTank()

        awaitUntil { events.isNotEmpty() }
        assertEquals(TankDetailUiEvent.Deleted, events.single())
        assertEquals(
            listOf(
                "dissolve categories $TANK Tank",
                "DELETE /api/tankoubons/$TANK",
                "cover choice written",
                "dissolve group $TANK",
            ),
            log.toList(),
        )
        assertNull(store.get(TANK))
    }

    @Test
    fun deleteTank_failureKeepsTheCoverChoiceAndTheGroup() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put(TANK, TankCoverChoiceStore.Choice(ID_EP1, page0 = 0, profileId = 5L))
        val vm = quietVm(store)
        val events = vm.uiEvent.collectInto(eventScope)
        deleteStatus = 423

        vm.deleteTank()

        awaitUntil { events.isNotEmpty() }
        assertEquals(TankDetailUiEvent.ShowError(ctx.getString(R.string.tank_locked)), events.single())
        awaitViewModelIdle(vm)
        assertEquals(listOf("dissolve categories $TANK Tank", "DELETE /api/tankoubons/$TANK"), log.toList())
        assertEquals(TankCoverChoiceStore.Choice(ID_EP1, 0, 5L), store.get(TANK))
    }

    @Test
    fun deleteTank_tellsTheListAndThePageUnderneath_onlyOnceTheDeleteSucceeded() {
        // The tank detail page waiting underneath closes on this event, and the
        // gallery list drops the tank's row (#98).
        val deleted = AppEventBus.archiveDeletedEvent.collectInto(eventScope)
        val vm = quietVm()
        val events = vm.uiEvent.collectInto(eventScope)
        deleteStatus = 423
        vm.deleteTank()
        awaitUntil { events.isNotEmpty() }

        deleteStatus = 200
        vm.deleteTank()

        awaitUntil { events.contains(TankDetailUiEvent.Deleted) }
        awaitUntil(message = "no deletion event") { deleted.isNotEmpty() }
        assertEquals(listOf(ArchiveDeletedEvent(TANK)), deleted.toList())
    }

    private companion object {
        const val TANK = "TANK_0000000001"
        val ID_EP1 = "1".padStart(40, '0')
        val ID_EP2 = "2".padStart(40, '0')
        val ID_EXTRA = "3".padStart(40, '0')
        val ID_LOOSE = "4".padStart(40, '0')
    }
}
