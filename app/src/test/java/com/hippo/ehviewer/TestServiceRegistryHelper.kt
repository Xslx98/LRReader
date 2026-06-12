package com.hippo.ehviewer

import android.content.Context
import androidx.room.Room
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * Test scope for code under test that launches fire-and-forget coroutines
 * (e.g. constructing a real DownloadManager, whose init fires
 * syncRatingsFromServer on the injected scope).
 *
 * The CoroutineExceptionHandler is load-bearing for suite stability, not
 * hygiene: test fakes that throw NotImplementedError("not needed") from
 * unimplemented getters DO get reached by those coroutines, and
 * NotImplementedError is an Error — the production `catch (e: Exception)`
 * can't contain it. Without a handler the Error escapes to the global
 * handler, where kotlinx-coroutines-test's collector picks it up and fails
 * whichever unrelated runTest runs NEXT with UncaughtExceptionsBeforeTest
 * (the victim drifts with suite ordering). Mirrors the production
 * CoroutineModule scopes, which all carry a handler.
 */
fun containedTestScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Unconfined +
        CoroutineExceptionHandler { _, t ->
            println("testScope contained: $t")
        }
)

/**
 * Test helper that initializes [ServiceRegistry] with test-configured modules.
 *
 * Provides:
 * - In-memory Room database (no persistence between tests)
 * - OkHttpClient pointing to a [MockWebServer]
 * - CoroutineModule with test exception handling
 *
 * Usage:
 * ```kotlin
 * @Before
 * fun setUp() {
 *     server = MockWebServer()
 *     server.start()
 *     helper = TestServiceRegistryHelper(context, server)
 *     helper.initialize()
 * }
 *
 * @After
 * fun tearDown() {
 *     helper.close()
 *     server.shutdown()
 * }
 * ```
 */
class TestServiceRegistryHelper(
    private val context: Context,
    private val mockWebServer: MockWebServer? = null
) {
    lateinit var db: AppDatabase
        private set
    lateinit var client: OkHttpClient
        private set

    fun initialize() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        ServiceRegistry.initializeForTest(CoroutineModule())
    }

    fun getBaseUrl(): String {
        return mockWebServer?.url("")?.toString()?.removeSuffix("/") ?: "http://localhost"
    }

    fun close() {
        if (::db.isInitialized) db.close()
    }
}
