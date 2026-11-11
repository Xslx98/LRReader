package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import androidx.collection.LruCache
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.FavoriteState
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.server.OrphanProfileException
import com.lanraragi.reader.server.ProfileLookupCache
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubDataModule
import com.lanraragi.reader.stubNetworkModule
import com.lanraragi.reader.tankoubon.TankListCache
import java.io.File
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * The detail request of an archive whose source server is not the active one
 * (server-and-network.md, "multi-profile"): every call goes to the SOURCE
 * server named by the navigation archive, the result is tagged with that
 * server, and every way the request can end publishes an outcome, so the page
 * never stays on its spinner.
 *
 * Two servers answer: `active` is the profile the user has selected, `source`
 * owns the archive. The reading preload is switched off in [request]: it has
 * its own owner (ReaderPageCache) and would add page requests here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryDetailViewModelSourceRoutingTest {

    /** A LANraragi that serves one archive; [overrides] replaces the answer for a path suffix. */
    private class FakeLrr : Dispatcher() {
        val paths = CopyOnWriteArrayList<String>()
        val overrides = ConcurrentHashMap<String, Int>()

        /** Holds the metadata answer so a test can act while the request is in flight. */
        @Volatile
        var metadataGate: CountDownLatch? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            paths += path
            overrides.entries.firstOrNull { path.endsWith(it.key) }?.let {
                return MockResponse().setResponseCode(it.value).setBody("""{"error":"nope"}""")
            }
            return when {
                path.endsWith("/metadata") -> {
                    metadataGate?.await(GATE_SECONDS, TimeUnit.SECONDS)
                    MockResponse().setBody(METADATA_JSON)
                }
                path.endsWith("/categories") -> MockResponse().setBody(CATEGORIES_JSON)
                path.endsWith("/tankoubons") && path.startsWith("/api/archives") ->
                    MockResponse().setBody("""{"tankoubons":["$TANK_ID"],"success":1}""")
                path == "/api/tankoubons" -> MockResponse().setBody(TANKS_JSON)
                path == "/api/info" -> MockResponse().setBody("""{"name":"x"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val activeLrr = FakeLrr()
    private val sourceLrr = FakeLrr()
    private lateinit var activeServer: MockWebServer
    private lateinit var sourceServer: MockWebServer
    private lateinit var activeUrl: String
    private lateinit var sourceUrl: String
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var detailCache: LruCache<String, ArchiveDetail>
    private var sourceProfileId = 0L
    private var activeProfileId = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx: Context = ApplicationProvider.getApplicationContext()
        activeServer = MockWebServer().apply { dispatcher = activeLrr; start() }
        sourceServer = MockWebServer().apply { dispatcher = sourceLrr; start() }
        activeUrl = activeServer.url("").toString().removeSuffix("/")
        sourceUrl = sourceServer.url("").toString().removeSuffix("/")

        val prefs = ctx.getSharedPreferences("detail_vm_source_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(prefs)

        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = ProfileRepository(db.miscDao())
        scope = containedTestScope()
        val lookup = ProfileLookupCache(repo, scope)
        runBlocking {
            activeProfileId = repo.insert(ServerProfile(name = "A", url = activeUrl))
            sourceProfileId = repo.insert(ServerProfile(name = "B", url = sourceUrl))
        }
        awaitUntil { lookup.snapshot.value.size == 2 }
        ServerSession.setServerUrl(activeUrl)
        ServerSession.setActiveProfileId(activeProfileId)

        detailCache = LruCache(4)
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        ServiceRegistry.initializeForTest(
            network = stubNetworkModule(client, File(ctx.cacheDir, "test-cache")),
            app = stubAppModule(ctx),
            data = object : IDataModule by stubDataModule() {
                override val profileLookupCache = lookup
                override val archiveDetailCache = detailCache
                override val archiveFavoriteCache = LruCache<String, FavoriteState>(4)
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        db.close()
        SecureCredentialStore.clear()
        TankoubonSupportGate.clear()
        TankListCache.invalidate(activeUrl)
        TankListCache.invalidate(sourceUrl)
        ServiceRegistry.initializeForTest(data = stubDataModule())
        activeServer.shutdown()
        sourceServer.shutdown()
    }

    private fun navArchive(profileId: Long) = Archive(
        arcid = ARCID,
        title = "from nav",
        tags = emptyMap(),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = profileId,
    )

    /** Starts the request with the reading preload suppressed, as when the user left the page. */
    private fun request(vm: GalleryDetailViewModel, lrr: FakeLrr = sourceLrr) {
        val gate = CountDownLatch(1)
        lrr.metadataGate = gate
        assertTrue(vm.requestGalleryDetail(" etc", " categories"))
        vm.cancelReadingPreload()
        gate.countDown()
    }

    @Test
    fun crossServerRequest_goesToTheSourceServer_andTagsTheResultWithIt() {
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))

        request(vm)
        awaitUntil { vm.archiveTankoubons.value != null }

        assertEquals(
            setOf(
                "/api/archives/$ARCID/metadata",
                "/api/archives/$ARCID/categories",
                "/api/archives/$ARCID/tankoubons",
                "/api/tankoubons",
            ),
            sourceLrr.paths.toSet(),
        )
        assertEquals("the active server was contacted", emptyList<String>(), activeLrr.paths.toList())

        val archive = vm.archiveDetail.value!!.archive
        assertEquals(sourceProfileId, archive.serverProfileId)
        assertTrue(archive.thumbnailUrl, archive.thumbnailUrl.startsWith(sourceUrl))
        assertEquals(sourceProfileId, detailCache.get(GalleryDetailViewModel.detailCacheKey(sourceProfileId, ARCID))!!.archive.serverProfileId)
        assertEquals(archive, vm.detailLoaded.replayCache.single().archive)
        assertEquals(listOf(TANK_ID), vm.archiveTankoubons.value!!.map { it.id })
        assertEquals(TankoubonSupportGate.Support.SUPPORTED, TankoubonSupportGate.support(sourceUrl))
    }

    @Test
    fun aTankDeletedElsewhere_leavesTheTankRow_butKeepsTheSection() {
        // #105: the tank's pages opened from this row (or a list batch) delete
        // it while this page waits underneath.
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))
        request(vm)
        awaitUntil { vm.archiveTankoubons.value != null }

        // Main is unconfined here: each event is applied before the post returns.
        AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(OTHER_ARCID))
        assertEquals("another deletion took the tank", listOf(TANK_ID), vm.archiveTankoubons.value!!.map { it.id })
        AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(TANK_ID))

        awaitUntil(message = "the deleted tank is still shown") { vm.archiveTankoubons.value!!.isEmpty() }
    }

    @Test
    fun heart_countsOnlyStaticCategoriesHoldingTheArchive() {
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))

        request(vm)
        awaitUntil { vm.favoriteState.value != null }

        assertEquals(FavoriteState(isFavorited = true, name = "Static one etc2 categories"), vm.favoriteState.value)
    }

    @Test
    fun aDetailTaggedWithTheActiveProfile_doesNotPullTheRequestAwayFromTheSource() {
        // A polluted LRU entry (1.12.7): the snapshot says "active", the nav arg says "source".
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))
        vm.setArchiveDetail(
            ArchiveDetail(navArchive(activeProfileId), tagGroups = emptyList(), language = null, size = null),
        )

        request(vm)
        awaitUntil { vm.detailLoaded.replayCache.isNotEmpty() }

        assertEquals("the active server was contacted", emptyList<String>(), activeLrr.paths.toList())
        assertEquals(sourceProfileId, vm.archiveDetail.value!!.archive.serverProfileId)
    }

    @Test
    fun aFailedCategoryLookup_keepsTheKnownHeart_andStillLoads() {
        sourceLrr.overrides["/categories"] = HTTP_BAD_REQUEST
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))
        val known = FavoriteState(isFavorited = true, name = "Known")
        vm.updateFavoriteState(known)

        request(vm)
        awaitUntil { vm.detailLoaded.replayCache.isNotEmpty() }

        assertEquals(known, vm.favoriteState.value)
        assertTrue(vm.detailErrorBanner.replayCache.isEmpty())
    }

    @Test
    fun aServerWithoutTheTankRoutes_isMarkedOnTheSourceUrl_hidesTheRow_andIsNotAskedAgain() {
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))
        request(vm)
        awaitUntil { vm.archiveTankoubons.value != null }

        sourceLrr.overrides["/api/archives/$ARCID/tankoubons"] = HTTP_NOT_FOUND
        vm.loadArchiveTankoubons()
        awaitUntil { vm.archiveTankoubons.value == null }

        assertTrue(TankoubonSupportGate.isUnsupported(sourceUrl))
        assertEquals(TankoubonSupportGate.Support.UNKNOWN, TankoubonSupportGate.support(activeUrl))

        val asked = sourceLrr.paths.size
        vm.loadArchiveTankoubons()
        // Two children stay for good: localReadingPage's sharing coroutine and
        // the deleted-tank collector.
        awaitUntil { vm.viewModelScope.coroutineContext.job.children.count { !it.isCompleted } <= 2 }
        assertEquals(asked, sourceLrr.paths.size)
    }

    @Test
    fun metadataRejected_sourceAlive_publishesTheOriginalErrorAsABanner() {
        sourceLrr.overrides["/metadata"] = HTTP_BAD_REQUEST
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))

        request(vm)
        awaitUntil { vm.detailErrorBanner.replayCache.isNotEmpty() }

        assertTrue(vm.detailErrorBanner.replayCache.single() is LRRHttpException)
        assertEquals(sourceProfileId, vm.archiveDetail.value!!.archive.serverProfileId)
        assertTrue(vm.detailError.replayCache.isEmpty())
    }

    @Test
    fun metadataRejected_sourceDown_readsAsUnreachable_evenWithTheActiveServerUp() {
        sourceLrr.overrides["/metadata"] = HTTP_BAD_REQUEST
        sourceLrr.overrides["/api/info"] = HTTP_BAD_GATEWAY
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId))

        request(vm)
        awaitUntil { vm.detailErrorBanner.replayCache.isNotEmpty() }

        assertTrue(vm.detailErrorBanner.replayCache.single() is ConnectException)
        assertEquals("the active server was probed", emptyList<String>(), activeLrr.paths.toList())
    }

    @Test
    fun aDeletedSourceProfile_publishesAnOutcome_insteadOfHanging() {
        val vm = GalleryDetailViewModel()
        vm.setArchive(navArchive(sourceProfileId + DELETED_PROFILE_OFFSET))

        request(vm)
        awaitUntil { vm.detailErrorBanner.replayCache.isNotEmpty() }

        assertTrue(vm.detailErrorBanner.replayCache.single() is OrphanProfileException)
        assertEquals(emptyList<String>(), sourceLrr.paths.toList() + activeLrr.paths)
    }

    @Test
    fun nothingToShow_publishesTheFullScreenError() {
        // Arcid only, no navigation archive: legacy profile 0 means the active server.
        activeLrr.overrides["/metadata"] = HTTP_BAD_REQUEST
        val vm = GalleryDetailViewModel()
        vm.setArcid(ARCID)

        request(vm, activeLrr)
        awaitUntil { vm.detailError.replayCache.isNotEmpty() }

        assertTrue(vm.detailError.replayCache.single() is LRRHttpException)
        assertTrue(vm.detailErrorBanner.replayCache.isEmpty())
        assertNull(vm.archiveDetail.value)
    }

    private companion object {
        const val GATE_SECONDS = 5L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_GATEWAY = 502
        const val DELETED_PROFILE_OFFSET = 1000L
        const val ARCID = "abcdef0123456789abcdef0123456789abcdef01"
        const val OTHER_ARCID = "1111111111111111111111111111111111111111"
        const val TANK_ID = "TANK_0000000201"

        const val METADATA_JSON =
            """{"arcid":"$ARCID","title":"From server","tags":"artist:x","pagecount":3,"progress":0,""" +
                """"lastreadtime":0,"isnew":"false","extension":"zip","filename":"a.zip"}"""

        const val CATEGORIES_JSON =
            """{"categories":[""" +
                """{"id":"SET_0000000001","name":"Static one","archives":["$ARCID"],"pinned":"0","search":""},""" +
                """{"id":"SET_0000000002","name":"Dynamic","archives":["$ARCID"],"pinned":"0","search":"x"},""" +
                """{"id":"SET_0000000003","name":"Other","archives":["$OTHER_ARCID"],"pinned":"0","search":""},""" +
                """{"id":"SET_0000000004","name":"Static two","archives":["$ARCID"],"pinned":"1","search":""}""" +
                """],"success":1}"""

        const val TANKS_JSON =
            """{"result":[""" +
                """{"id":"$TANK_ID","name":"Holds it","archives":["$ARCID"],"summary":null,"tags":null,"progress":0},""" +
                """{"id":"TANK_0000000202","name":"Other","archives":[],"summary":null,"tags":null,"progress":0}""" +
                """],"total":2,"filtered":2}"""
    }
}
