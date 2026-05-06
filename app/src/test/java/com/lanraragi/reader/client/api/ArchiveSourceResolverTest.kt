package com.lanraragi.reader.client.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [resolveSourceBaseUrl].
 *
 * Verifies the routing rules documented on the helper:
 *  - id == 0 + active URL configured  → returns active URL
 *  - id == 0 + no active URL          → throws [OrphanProfileException]
 *  - id != 0 + cache hit              → returns the matching profile's URL
 *  - id != 0 + cache miss             → throws [OrphanProfileException]
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ArchiveSourceResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProfileRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProfileRepository(db.miscDao())
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // Wire LRRAuthManager onto a clean SharedPreferences so the legacy
        // (id == 0) fallback path is testable without touching KeyStore.
        val prefs = ctx.getSharedPreferences("lrr_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        LRRAuthManager.initializeForTesting(prefs)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        // Leave LRRAuthManager state behind for the next test's setUp() to wipe.
    }

    private fun newCache(): ProfileLookupCache = ProfileLookupCache(repo, scope)

    private suspend fun awaitSnapshotSize(cache: ProfileLookupCache, size: Int) {
        withTimeout(2_000) {
            while (cache.snapshot.value.size != size) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    @Test
    fun legacyId_returnsActiveServerUrl() = runBlocking {
        LRRAuthManager.setServerUrl("http://active.local:3000")
        val cache = newCache()
        cache.awaitInitialized()

        val url = resolveSourceBaseUrl(0L, cache)
        assertEquals("http://active.local:3000", url)
    }

    @Test
    fun legacyId_throwsOrphanWhenActiveUrlUnset() = runBlocking {
        // No setServerUrl call → getServerUrl() returns null → orphan(0).
        val cache = newCache()
        cache.awaitInitialized()

        val ex = assertThrows(OrphanProfileException::class.java) {
            runBlocking { resolveSourceBaseUrl(0L, cache) }
        }
        assertEquals(0L, ex.profileId)
    }

    @Test
    fun knownId_returnsSourceProfileUrl() = runBlocking {
        val id = repo.insert(ServerProfile(name = "B", url = "http://server-b.local:3000"))
        val cache = newCache()
        awaitSnapshotSize(cache, 1)

        val url = resolveSourceBaseUrl(id, cache)
        assertEquals("http://server-b.local:3000", url)
    }

    @Test
    fun unknownId_throwsOrphanProfileException() = runBlocking {
        val cache = newCache()
        cache.awaitInitialized()

        val ex = assertThrows(OrphanProfileException::class.java) {
            runBlocking { resolveSourceBaseUrl(9999L, cache) }
        }
        assertEquals(9999L, ex.profileId)
    }
}
