package com.lanraragi.reader.ui.scene.tankdetail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.AppProxySelector
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.module.IAppModule
import com.lanraragi.reader.module.INetworkModule
import com.lanraragi.reader.module.NetworkMonitor
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailViewModel.LoadState
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailViewModel.OfflineTank
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TankDetailViewModel] load contract (spec 2026-09-22 §4, plan A-1):
 * `/full` → members in `archives` order, totals + page offsets, the
 * tank's OWN tags/rating, the category heart from static categories;
 * a failed fetch falls back to the downloaded-tank snapshot (offline
 * mode, editing disabled); nothing local → error; 404 → unsupported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankDetailViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var ctx: Context
    private lateinit var eventScope: CoroutineScope

    @Volatile
    private var fullStatus = 200

    @Volatile
    private var categoriesStatus = 200

    @Volatile
    private var tankTags: String? = "artist:foo, rating:4, language:english"

    @Volatile
    private var putStatus = 200

    @Volatile
    private var deleteStatus = 200

    private val deletes = AtomicInteger(0)

    /** `metadata.tags` of every PUT /api/tankoubons/{id} the mock received. */
    private val putTags = CopyOnWriteArrayList<String>()

    @Volatile
    private var categoriesJson = """[
        {"id":"SET_STATIC","name":"Favs","archives":["$TANK"],"pinned":"0","search":""},
        {"id":"SET_DYN","name":"Dyn","archives":["$TANK"],"pinned":"0","search":"foo"}
    ]"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ctx = ApplicationProvider.getApplicationContext()
        TankoubonSupportGate.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "PUT" && path == "/api/tankoubons/$TANK" -> {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        putTags.add(body.getValue("metadata").jsonObject.getValue("tags").jsonPrimitive.content)
                        if (putStatus == 200) {
                            MockResponse().setBody("""{"success":1}""")
                        } else {
                            MockResponse().setResponseCode(putStatus)
                        }
                    }
                    request.method == "DELETE" && path == "/api/tankoubons/$TANK" -> {
                        deletes.incrementAndGet()
                        if (deleteStatus == 200) {
                            MockResponse().setBody("""{"success":1}""")
                        } else {
                            MockResponse().setResponseCode(deleteStatus)
                        }
                    }
                    path.startsWith("/api/tankoubons/$TANK/full") ->
                        if (fullStatus == 200) {
                            MockResponse().setBody(fullJson())
                        } else {
                            MockResponse().setResponseCode(fullStatus)
                        }
                    path.startsWith("/api/tankoubons/$TANK/thumbnail") -> MockResponse().setBody("x")
                    path.startsWith("/api/categories") ->
                        if (categoriesStatus == 200) {
                            MockResponse().setBody(categoriesJson)
                        } else {
                            MockResponse().setResponseCode(categoriesStatus)
                        }
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
        LRRAuthManager.initializeForTesting(ctx.getSharedPreferences("tank_detail_test", Context.MODE_PRIVATE))
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
        TankoubonSupportGate.clear()
        server.shutdown()
    }

    private fun member(id: String, title: String, pages: Int) =
        """{"arcid":"$id","title":"$title","tags":"artist:foo, rating:2","lastreadtime":0,"progress":0,""" +
            """"pagecount":$pages,"isnew":"false","extension":"zip","filename":"$id.zip","summary":""}"""

    /** full_data deliberately in a DIFFERENT order than `archives`. */
    private fun fullJson(): String {
        val tags = tankTags?.let { "\"$it\"" } ?: "null"
        return """{"result":{"id":"$TANK","name":"My Tank","summary":"s","tags":$tags,"progress":12,""" +
            """"archives":["$ID_A","$ID_B","$ID_C"],""" +
            """"full_data":[${member(ID_C, "c", 30)},${member(ID_A, "a", 10)},${member(ID_B, "b", 20)}]},""" +
            """"total":1,"filtered":1}"""
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun newVm(offline: OfflineTank? = null): TankDetailViewModel {
        val vm = TankDetailViewModel()
        vm.baseUrlResolver = { LRRAuthManager.getServerUrl()!! }
        vm.offlineSource = { _, _ -> offline }
        vm.membershipSync = TankMembershipSyncFactory.Runner { _, _, _ -> }
        vm.forgetCoverChoice = { forgotten.add(it) }
        vm.dissolveDownloadGroup = { dissolved.add(it) }
        // Category promotion has its own wire tests (TankCategorySyncerTest); record the calls here.
        vm.categoryDissolve = { _, _, id, name -> categoryCalls.add("dissolve $id $name"); true }
        vm.resetCategoriesOp = { _, _, id, _, members -> categoryCalls.add("reset $id ${members.size}"); true }
        vm.init(TANK, "Seed Name", profileId = 0L)
        return vm
    }

    private val forgotten = CopyOnWriteArrayList<String>()
    private val dissolved = CopyOnWriteArrayList<String>()
    private val categoryCalls = CopyOnWriteArrayList<String>()

    private fun awaitSettled(vm: TankDetailViewModel) =
        awaitCondition { vm.loadState.value !is LoadState.Loading && vm.loadState.value !is LoadState.Idle }

    @Test
    fun load_ordersMembersByArchivesAndComputesTotals() {
        val vm = newVm()
        vm.load()
        awaitSettled(vm)

        val state = vm.state.value
        assertNotNull(state)
        state!!
        assertTrue(vm.loadState.value is LoadState.Loaded)
        assertEquals("My Tank", state.name)
        assertEquals(listOf(ID_A, ID_B, ID_C), state.members.map { it.arcid })
        assertEquals(60, state.totalPages)
        assertEquals(listOf(0, 10, 30, 60), state.pageOffsets)
        assertEquals(3, state.memberCount)
        assertEquals(12, state.progress)
        assertFalse(state.offline)
        assertEquals(0L, state.members.first().serverProfileId)
    }

    @Test
    fun load_exposesTankOwnTagsAndRatingNotMembers() {
        val vm = newVm()
        vm.load()
        awaitSettled(vm)

        val state = vm.state.value!!
        assertEquals("artist:foo, rating:4, language:english", state.tags)
        assertEquals(4f, state.rating, 0f)
        assertEquals(
            listOf(TagGroup("artist", listOf("foo")), TagGroup("rating", listOf("4")), TagGroup("language", listOf("english"))),
            state.tagGroups,
        )
    }

    @Test
    fun load_nullTagsAutoFillTheMembersUnionOnceAndStayUnrated() {
        tankTags = null
        val vm = newVm()
        vm.load()
        awaitSettled(vm)

        // §4.5: legacy tank without tags of its own → members' union written
        // silently (rating namespace excluded) and rendered at once.
        val state = vm.state.value!!
        assertEquals("artist:foo", putTags.single())
        assertEquals("artist:foo", state.tags)
        assertEquals("no rating tag = the shared -1 sentinel, as for archives", -1f, state.rating, 0f)
        assertEquals(listOf(TagGroup("artist", listOf("foo"))), state.tagGroups)
    }

    @Test
    fun load_taggedTankIsNotAutoFilled() {
        val vm = loadedVm()

        assertTrue(putTags.isEmpty())
        assertEquals("artist:foo, rating:4, language:english", vm.state.value!!.tags)
    }

    @Test
    fun resetTags_rewritesExcludedTankTagsPlusTheUnionThenReloads() {
        val vm = loadedVm()

        vm.resetTags()

        awaitCondition { putTags.size == 1 }
        assertEquals("rating:4, artist:foo", putTags.single())
        awaitCondition { categoryCalls.isNotEmpty() }
        assertEquals(listOf("reset $TANK 3"), categoryCalls)
        awaitCondition { vm.loadState.value is LoadState.Loaded && !vm.state.value!!.offline }
    }

    @Test
    fun resetTags_isIgnoredOffline() {
        fullStatus = 500
        val vm = newVm(offline = OfflineTank("T", listOf(archive(ID_A, "a", 10))))
        vm.load()
        awaitSettled(vm)

        vm.resetTags()

        Thread.sleep(200)
        assertTrue(putTags.isEmpty())
    }

    @Test
    fun load_heartFollowsStaticCategoriesOnly() {
        val vm = newVm()
        vm.load()
        awaitSettled(vm)
        awaitCondition { vm.favoriteState.value != null }

        val fav = vm.favoriteState.value!!
        assertTrue(fav.isFavorited)
        assertEquals("Favs", fav.name)
    }

    @Test
    fun load_categoriesFailureIsNonFatal() {
        categoriesStatus = 500
        val vm = newVm()
        vm.load()
        awaitSettled(vm)

        assertTrue(vm.loadState.value is LoadState.Loaded)
        assertEquals(3, vm.state.value!!.members.size)
        assertNull(vm.favoriteState.value)
    }

    @Test
    fun load_fetchFailureFallsBackToDownloadedSnapshot() {
        fullStatus = 500
        val snapshot = OfflineTank(
            name = "Offline Tank",
            members = listOf(archive(ID_B, "b", 20), archive(ID_A, "a", 10)),
        )
        val vm = newVm(offline = snapshot)
        vm.load()
        awaitSettled(vm)

        assertTrue(vm.loadState.value is LoadState.Loaded)
        val state = vm.state.value!!
        assertTrue(state.offline)
        assertEquals("Offline Tank", state.name)
        assertEquals(listOf(ID_B, ID_A), state.members.map { it.arcid })
        assertEquals(30, state.totalPages)
        assertEquals(listOf(0, 20, 30), state.pageOffsets)
        assertEquals("", state.tags)
        assertNull(vm.favoriteState.value)
    }

    @Test
    fun load_fetchFailureWithoutSnapshotIsAnError() {
        fullStatus = 500
        val vm = newVm(offline = null)
        vm.load()
        awaitSettled(vm)

        assertTrue(vm.loadState.value is LoadState.Error)
        assertNull(vm.state.value)
    }

    @Test
    fun load_404FlipsSupportGateAndReportsUnsupported() {
        fullStatus = 404
        val vm = newVm(offline = null)
        vm.load()
        awaitSettled(vm)

        assertTrue(vm.loadState.value is LoadState.Unsupported)
        assertTrue(TankoubonSupportGate.isUnsupported(LRRAuthManager.getServerUrl()!!))
    }

    @Test
    fun load_404WithSnapshotStillOpensOffline() {
        fullStatus = 404
        val vm = newVm(offline = OfflineTank("T", listOf(archive(ID_A, "a", 10))))
        vm.load()
        awaitSettled(vm)

        assertTrue(vm.loadState.value is LoadState.Loaded)
        assertTrue(vm.state.value!!.offline)
    }

    @Test
    fun init_isIdempotent() {
        val vm = newVm()
        vm.init("TANK_other", "Other", profileId = 9L)
        assertEquals(TANK, vm.tankId)
        assertEquals(0L, vm.profileId)
        assertEquals("Seed Name", vm.seedName)
    }

    // ---- rating (spec §4.2) ----

    private fun collectErrors(vm: TankDetailViewModel): CopyOnWriteArrayList<String> {
        val events = CopyOnWriteArrayList<String>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            vm.ratingError.onSubscription { subscribed.complete(Unit) }.collect { events.add(it) }
        }
        runBlocking { subscribed.await() }
        return events
    }

    private fun loadedVm(): TankDetailViewModel {
        val vm = newVm()
        vm.load()
        awaitSettled(vm)
        return vm
    }

    @Test
    fun submitRating_putsWholeTagStringWithRatingSlotReplaced() {
        val vm = loadedVm()
        assertEquals(4f, vm.initialRating, 0f)

        vm.submitRating(2f)

        // Optimistic: the state shows the new rating before the PUT lands.
        assertEquals(2f, vm.state.value!!.rating, 0f)
        awaitCondition { putTags.size == 1 }
        assertEquals("artist:foo, language:english, rating:⭐⭐", putTags.single())
        assertEquals(listOf("artist", "language", "rating"), vm.state.value!!.tagGroups.map { it.namespace })
        assertEquals("initial rating is the loaded value, not the edit", 4f, vm.initialRating, 0f)
    }

    @Test
    fun submitRating_failureRollsBackTagsAndReportsError() {
        putStatus = 500
        val vm = loadedVm()
        val errors = collectErrors(vm)

        vm.submitRating(1f)

        awaitCondition { errors.size == 1 }
        assertEquals("artist:foo, rating:4, language:english", vm.state.value!!.tags)
        assertEquals(4f, vm.state.value!!.rating, 0f)
    }

    @Test
    fun submitRating_sameValueIsANoOp() {
        val vm = loadedVm()

        vm.submitRating(4f)

        Thread.sleep(200)
        assertTrue(putTags.isEmpty())
    }

    @Test
    fun submitRating_zeroStripsTheRatingTag() {
        val vm = loadedVm()

        vm.submitRating(0f)

        awaitCondition { putTags.size == 1 }
        assertEquals("artist:foo, language:english", putTags.single())
        assertEquals(-1f, vm.state.value!!.rating, 0f)
    }

    @Test
    fun submitRating_isIgnoredOffline() {
        fullStatus = 500
        val vm = newVm(offline = OfflineTank("T", listOf(archive(ID_A, "a", 10))))
        vm.load()
        awaitSettled(vm)

        vm.submitRating(3f)

        Thread.sleep(200)
        assertTrue(putTags.isEmpty())
        assertEquals("", vm.state.value!!.tags)
        assertTrue(vm.initialRating.isNaN())
    }

    // ---- delete (spec §4.8) ----

    private fun collectEvents(vm: TankDetailViewModel): CopyOnWriteArrayList<TankDetailViewModel.Event> {
        val events = CopyOnWriteArrayList<TankDetailViewModel.Event>()
        val subscribed = CompletableDeferred<Unit>()
        eventScope.launch {
            vm.events.onSubscription { subscribed.complete(Unit) }.collect { events.add(it) }
        }
        runBlocking { subscribed.await() }
        return events
    }

    @Test
    fun deleteTank_deletesServerSideThenForgetsCoverAndDissolvesGroup() {
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.deleteTank()

        awaitCondition { events.any { it is TankDetailViewModel.Event.Deleted } }
        assertEquals(1, deletes.get())
        assertEquals(listOf(TANK), forgotten)
        assertEquals(listOf(TANK), dissolved)
        assertEquals("categories cleared before the DELETE", listOf("dissolve $TANK My Tank"), categoryCalls)
    }

    @Test
    fun deleteTank_failureReportsErrorAndTouchesNothingLocal() {
        deleteStatus = 500
        val vm = loadedVm()
        val events = collectEvents(vm)

        vm.deleteTank()

        awaitCondition { events.any { it is TankDetailViewModel.Event.Error } }
        assertTrue(events.none { it is TankDetailViewModel.Event.Deleted })
        assertTrue(forgotten.isEmpty())
        assertTrue(dissolved.isEmpty())
    }

    @Test
    fun deleteTank_isIgnoredOffline() {
        fullStatus = 500
        val vm = newVm(offline = OfflineTank("T", listOf(archive(ID_A, "a", 10))))
        vm.load()
        awaitSettled(vm)

        vm.deleteTank()

        Thread.sleep(200)
        assertEquals(0, deletes.get())
    }

    private fun archive(id: String, title: String, pages: Int) = Archive(
        arcid = id, title = title, tags = emptyMap(), pagecount = pages, progress = 0,
        extension = "zip", filename = "$id.zip", thumbnailUrl = "", rating = 0f, isnew = false,
        lastreadtime = 0L, summary = null, serverProfileId = 0L,
    )

    private companion object {
        const val TANK = "TANK_1700000000"
        val ID_A = "a".repeat(40)
        val ID_B = "b".repeat(40)
        val ID_C = "c".repeat(40)
    }
}
