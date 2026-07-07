package com.hippo.ehviewer.gallery

import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageCacheCancelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val executor = Executors.newSingleThreadExecutor()

    // Long read timeout on purpose: only cancel() may unblock the stream.
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        executor.shutdownNow()
    }

    @Test
    fun cancelMidBody_throwsIOException_leavesNoCacheOrTmpFiles() {
        // 512 KB body throttled to 8 KB / 100 ms => ~6.4 s full stream.
        val body = Buffer().write(ByteArray(512 * 1024))
        server.enqueue(
            MockResponse().setBody(body).throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS)
        )
        val cacheFile = File(tmp.root, "page_0")
        val callRef = AtomicReference<Call>()

        val future = executor.submit<Throwable?> {
            try {
                ReaderPageCache.downloadToFile(
                    client, server.url("/page").toString(), cacheFile,
                    onCallCreated = { callRef.set(it) }
                )
                null
            } catch (t: Throwable) {
                t
            }
        }

        // Poll the exact asserted precondition: the Call exists and the
        // request has reached the server (body is streaming).
        assertNotNull("request never reached server", server.takeRequest(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (callRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val call = callRef.get()
        assertNotNull("onCallCreated never fired", call)

        call.cancel()

        val thrown = future.get(10, TimeUnit.SECONDS)
        assertTrue("expected IOException from severed stream, got $thrown", thrown is IOException)
        assertFalse("no partial cache file may survive a cancel", cacheFile.exists())
        val leftovers = tmp.root.listFiles()?.filter { it.name.contains(".tmp") }.orEmpty()
        assertTrue("tmp leftovers: $leftovers", leftovers.isEmpty())
    }
}
