package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.archive.MetadataPluginService
import com.lanraragi.reader.archive.TagWriter
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.awaitViewModelIdle
import com.lanraragi.reader.client.api.data.LRRServerInfo
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.server.ServerCapabilityCache
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.stubAppModule
import com.lanraragi.reader.stubDataModule
import com.lanraragi.reader.stubNetworkModule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The plugin screen's flow against a server: above all, that nothing short of
 * [PluginTagViewModel.apply] sends a PUT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PluginTagViewModelTest {

    private val profileId = 11L
    private val arcid = "a".repeat(40)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val main = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private lateinit var scope: CoroutineScope
    private lateinit var baseUrl: String

    @Volatile private var listCode = 200

    @Volatile private var job = FINISHED

    @Volatile private var putCode = 200

    @Volatile private var alreadyOnServer = "artist:old"

    private val writer = object : TagWriter {
        override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) =
            throw UnsupportedOperationException()

        override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String = alreadyOnServer
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        scope = containedTestScope()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PUT" -> MockResponse().setResponseCode(putCode).setBody("{}")
                    path == "/api/plugins/metadata" -> MockResponse().setResponseCode(listCode).setBody(PLUGINS)
                    path == "/api/plugins/queue" -> MockResponse().setBody("""{"job":5}""")
                    path == "/api/minion/5/detail" -> MockResponse().setBody(job)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        conn = ServerConnection(profileId, baseUrl, client)

        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(ctx.getSharedPreferences("plugin_vm_test", Context.MODE_PRIVATE))
        ServiceRegistry.initializeForTest(
            network = stubNetworkModule(client, File(ctx.cacheDir, "test-cache")),
            data = stubDataModule(),
            app = stubAppModule(ctx),
        )
        ServerCapabilityCache.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        SecureCredentialStore.clear()
        ServerCapabilityCache.clear()
        server.shutdown()
    }

    private fun archive() = Archive(
        arcid = arcid, title = "Old title", tags = emptyMap(), pagecount = 1, progress = 0,
        extension = "", filename = "", thumbnailUrl = "", rating = 0f, isnew = false,
        lastreadtime = 0L, summary = null, serverProfileId = profileId,
    )

    private fun viewModel() = PluginTagViewModel(MetadataPluginService(writer, scope)) { conn }

    private fun PluginTagViewModel.loaded(): List<MetadataPlugin> {
        init(archive())
        awaitViewModelIdle(this)
        return (state.value as PluginTagUiState.Plugins).plugins
    }

    private fun PluginTagViewModel.reviewing(): PluginTagUiState.Review {
        val plugin = loaded().first()
        run(plugin, null)
        awaitUntil { state.value is PluginTagUiState.Review }
        return state.value as PluginTagUiState.Review
    }

    private fun puts() = requests.filter { it.method == "PUT" }

    private fun setHasPassword(value: Boolean) {
        ServerCapabilityCache.recordServerInfo(baseUrl, LRRServerInfo().apply { hasPassword = value })
    }

    @Test
    fun review_startsWithTagsCheckedAndTextRowsUnchecked() {
        val review = viewModel().reviewing()

        assertEquals(listOf(ReviewTag("artist:new"), ReviewTag("series:s")), review.tags)
        assertEquals(ReviewText("Old title", "New title", checked = false), review.title)
        assertEquals(ReviewText("", "New summary", checked = false), review.summary)
        assertEquals(2, review.changeCount)
    }

    /** The whole flow short of the confirmation: list, run, review, toggles, back, teardown. */
    @Test
    fun anUnconfirmedFlow_neverSendsAPut() {
        val vm = viewModel()
        val review = vm.reviewing()
        vm.setTagChecked(review.tags.first().tag, false)
        vm.setTitleChecked(true)
        vm.setSummaryChecked(true)
        vm.back()
        assertTrue(vm.state.value is PluginTagUiState.Plugins)

        vm.run((vm.state.value as PluginTagUiState.Plugins).plugins.first(), "again")
        awaitUntil { vm.state.value is PluginTagUiState.Review }
        awaitViewModelIdle(vm)

        assertTrue(requests.any { it.path!!.startsWith("/api/minion/") })
        assertEquals(emptyList<RecordedRequest>(), puts())
    }

    @Test
    fun apply_writesOnlyWhatIsChecked() {
        val vm = viewModel()
        vm.reviewing()
        vm.setTagChecked("artist:new", false)

        vm.apply()
        awaitUntil { vm.state.value == PluginTagUiState.Applied(wrote = true) }

        val url = puts().single().requestUrl!!
        assertEquals("artist:old, series:s", url.queryParameter("tags"))
        assertNull(url.queryParameter("title"))
        assertNull(url.queryParameter("summary"))
    }

    @Test
    fun apply_includesATextRowOnceChecked() {
        val vm = viewModel()
        vm.reviewing()
        vm.setTitleChecked(true)
        assertEquals(3, (vm.state.value as PluginTagUiState.Review).changeCount)

        vm.apply()
        awaitUntil { vm.state.value == PluginTagUiState.Applied(wrote = true) }

        val url = puts().single().requestUrl!!
        assertEquals("New title", url.queryParameter("title"))
        assertNull(url.queryParameter("summary"))
    }

    @Test
    fun apply_withEverythingUnchecked_sendsNothing() {
        val vm = viewModel()
        val review = vm.reviewing()
        review.tags.forEach { vm.setTagChecked(it.tag, false) }

        vm.apply()
        awaitViewModelIdle(vm)

        assertTrue(vm.state.value is PluginTagUiState.Review)
        assertTrue(puts().isEmpty())
    }

    @Test
    fun apply_locked_keepsTheReviewAndItsChoices_andCanBeRetried() {
        putCode = 423
        val vm = viewModel()
        vm.reviewing()
        vm.setTagChecked("artist:new", false)
        vm.setTitleChecked(true)

        vm.apply()
        awaitUntil { (vm.state.value as? PluginTagUiState.Review)?.applyFailure != null }

        val kept = vm.state.value as PluginTagUiState.Review
        assertEquals(PluginTagFailure.Busy, kept.applyFailure)
        assertFalse(kept.applying)
        assertEquals(listOf(ReviewTag("artist:new", false), ReviewTag("series:s", true)), kept.tags)
        assertTrue(kept.title!!.checked)

        putCode = 200
        vm.apply()
        awaitUntil { vm.state.value == PluginTagUiState.Applied(wrote = true) }
        assertEquals(2, puts().size)
    }

    @Test
    fun apply_whenTheServerAlreadyHasIt_saysNothingWasWritten() {
        val vm = viewModel()
        val review = vm.reviewing()
        review.tags.forEach { vm.setTagChecked(it.tag, false) }
        vm.setTagChecked("artist:new", true)
        alreadyOnServer = "Artist:New"

        vm.apply()
        awaitUntil { vm.state.value is PluginTagUiState.Applied }

        assertEquals(PluginTagUiState.Applied(wrote = false), vm.state.value)
        assertTrue(puts().isEmpty())
    }

    /** A disabled box cannot flip itself away from what a retry would write. */
    @Test
    fun reviewRows_areDisabledOnlyDuringTheWrite() {
        val review = viewModel().reviewing()

        fun enabled(r: PluginTagUiState.Review) = pluginTagReviewRows(r).mapNotNull {
            when (it) {
                is PluginTagRow.Tag -> it.enabled
                is PluginTagRow.Title -> it.enabled
                is PluginTagRow.Summary -> it.enabled
                else -> null
            }
        }

        assertEquals(listOf(true, true, true, true), enabled(review))
        assertEquals(listOf(false, false, false, false), enabled(review.copy(applying = true)))
    }

    /** Leaving while it polls: a result arriving later neither reaches the screen nor writes. */
    @Test
    fun back_whilePolling_abandonsTheRun() {
        job = """{"state":"active"}"""
        val vm = viewModel()
        val plugin = vm.loaded().first()
        vm.run(plugin, null)
        awaitUntil { requests.any { it.path == "/api/minion/5/detail" } }

        vm.back()
        job = FINISHED
        main.scheduler.advanceUntilIdle()
        awaitViewModelIdle(vm)

        assertTrue(vm.state.value is PluginTagUiState.Plugins)
        assertTrue(puts().isEmpty())
        assertEquals(1, requests.count { it.path == "/api/minion/5/detail" })
    }

    @Test
    fun aPasswordServerAndNoKey_explainsWithoutAskingTheServer() {
        setHasPassword(true)
        val vm = viewModel()

        vm.init(archive())
        awaitViewModelIdle(vm)

        assertEquals(PluginTagUiState.Failed(PluginTagFailure.KeyRequired, canRetry = false), vm.state.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aPasswordServerAndAKey_lists() {
        setHasPassword(true)
        ServerSession.setApiKeyForProfile(profileId, "k")

        assertEquals(2, viewModel().loaded().size)
    }

    @Test
    fun noPasswordAndNoKey_lists() {
        setHasPassword(false)

        assertEquals(2, viewModel().loaded().size)
    }

    @Test
    fun unknownServerAndNoKey_asksAndA401Explains() {
        listCode = 401
        val vm = viewModel()

        vm.init(archive())
        awaitViewModelIdle(vm)

        assertEquals(PluginTagUiState.Failed(PluginTagFailure.KeyRequired, canRetry = false), vm.state.value)
        assertEquals(1, requests.size)
    }

    @Test
    fun a404List_isUnsupported_andRetryAsksAgain() {
        listCode = 404
        val vm = viewModel()
        vm.init(archive())
        awaitViewModelIdle(vm)
        assertEquals(PluginTagUiState.Failed(PluginTagFailure.Unsupported, canRetry = true), vm.state.value)

        listCode = 200
        vm.retry()
        awaitViewModelIdle(vm)

        assertTrue(vm.state.value is PluginTagUiState.Plugins)
    }

    private companion object {
        const val PLUGINS = """[{"name":"A","namespace":"a","version":"1"},{"name":"B","namespace":"b","version":"1"}]"""
        const val FINISHED = """{"state":"finished","result":{"success":1,"data":
            {"new_tags":" artist:new, series:s","title":"New title","summary":"New summary"}}}"""
    }
}
