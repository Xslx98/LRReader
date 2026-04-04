package com.hippo.ehviewer

import android.content.Context
import androidx.room.Room
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.module.CoroutineModule
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

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
