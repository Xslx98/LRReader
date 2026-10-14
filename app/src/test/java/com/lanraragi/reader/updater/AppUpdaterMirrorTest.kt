package com.lanraragi.reader.updater

import com.lanraragi.reader.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [AppUpdater.fetchRelease]: the China mirror's latest.json first, GitHub's
 * releases/latest on any mirror failure; only both failing is a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppUpdaterMirrorTest {

    private lateinit var server: MockWebServer
    private var mirror: MockResponse = MockResponse().setResponseCode(500)
    private var github: MockResponse = MockResponse().setResponseCode(500)
    private val client = OkHttpClient()

    private fun releaseJson(tag: String) = """{"tag_name":"$tag","assets":[]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/mirror/latest.json") mirror else github
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetch(): GhRelease? = runBlocking {
        AppUpdater.fetchRelease(
            client,
            mirrorUrl = server.url("/mirror/latest.json").toString(),
            githubUrl = server.url("/github/releases/latest").toString(),
        )
    }

    private fun githubRequests() = generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }
        .count { it.path == "/github/releases/latest" }

    @Test
    fun aWorkingMirrorAnswersAloneWithTheAppUserAgent() {
        mirror = MockResponse().setBody(releaseJson("v2.1.0"))

        assertEquals("v2.1.0", fetch()?.tagName)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/mirror/latest.json", request.path)
        assertEquals("LRReader/${BuildConfig.VERSION_NAME}", request.getHeader("User-Agent"))
        assertEquals(0, githubRequests())
    }

    @Test
    fun rateLimitedMirrorFallsBackToGitHub() {
        mirror = MockResponse().setResponseCode(429)
        github = MockResponse().setBody(releaseJson("v2.0.9"))

        assertEquals("v2.0.9", fetch()?.tagName)
        assertEquals(1, githubRequests())
    }

    @Test
    fun unparsableOrTaglessMirrorBodyFallsBackToGitHub() {
        github = MockResponse().setBody(releaseJson("v2.0.9"))

        mirror = MockResponse().setBody("<html>not json</html>")
        assertEquals("v2.0.9", fetch()?.tagName)

        mirror = MockResponse().setBody("{}")
        assertEquals("v2.0.9", fetch()?.tagName)
    }

    @Test
    fun unreachableMirrorFallsBackToGitHub() {
        github = MockResponse().setBody(releaseJson("v2.0.9"))
        // A port nothing listens on: connection refused.
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/latest.json").toString()
        dead.shutdown()

        val release = runBlocking {
            AppUpdater.fetchRelease(client, mirrorUrl = deadUrl, githubUrl = server.url("/github/releases/latest").toString())
        }

        assertEquals("v2.0.9", release?.tagName)
    }

    @Test
    fun bothFailingIsAFailure() {
        mirror = MockResponse().setResponseCode(503)
        github = MockResponse().setResponseCode(503)
        assertNull(fetch())

        // A GitHub network error still surfaces as before (the caller maps it to NetworkError).
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/x").toString()
        dead.shutdown()
        assertThrows(IOException::class.java) {
            runBlocking { AppUpdater.fetchRelease(client, mirrorUrl = deadUrl, githubUrl = deadUrl) }
        }
    }
}
