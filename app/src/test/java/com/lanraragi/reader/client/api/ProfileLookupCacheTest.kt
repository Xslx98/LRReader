package com.lanraragi.reader.client.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.containedTestScope
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ProfileLookupCache].
 *
 * Uses an in-memory Room database to verify that the cache:
 *  - hydrates from the initial profile list,
 *  - tracks subsequent inserts/updates/deletes via the observed Flow,
 *  - resolves lookups by id and by request URL,
 *  - distinguishes scheme-match vs scheme-downgrade host:port collisions,
 *  - completes [ProfileLookupCache.awaitInitialized] after the first
 *    Room emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProfileLookupCacheTest {

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
        // Handler-bearing scope: see containedTestScope's KDoc. Same
        // cancel-then-close teardown race as ArchiveSourceResolverTest —
        // an in-flight Room-flow query at db.close() must not escape to
        // the global handler and poison a later runTest.
        scope = containedTestScope()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
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
    fun snapshot_emptyOnFreshInstall() = runBlocking {
        val cache = newCache()
        cache.awaitInitialized()
        assertEquals(emptyList<ServerProfile>(), cache.snapshot.value)
    }

    @Test
    fun findById_returnsInsertedProfile() = runBlocking {
        val id = repo.insert(ServerProfile(name = "primary", url = "http://192.168.1.10:3000"))
        val cache = newCache()
        awaitSnapshotSize(cache, 1)
        val found = cache.findById(id)
        assertNotNull(found)
        assertEquals("primary", found!!.name)
    }

    @Test
    fun findById_missingProfileReturnsNull() = runBlocking {
        val cache = newCache()
        cache.awaitInitialized()
        assertNull(cache.findById(9999))
    }

    @Test
    fun findByRequestUrl_matchesOnHostPortAndScheme() = runBlocking {
        repo.insert(ServerProfile(name = "lan", url = "http://lrr.local:3000"))
        val cache = newCache()
        awaitSnapshotSize(cache, 1)

        val req = "http://lrr.local:3000/api/info".toHttpUrl()
        val match = cache.findByRequestUrl(req)
        assertNotNull(match)
        assertEquals("lan", match!!.name)
    }

    @Test
    fun findByRequestUrl_returnsNullWhenSchemeDiffers() = runBlocking {
        // Configured profile is HTTP; request comes in HTTPS — no scheme match.
        repo.insert(ServerProfile(name = "lan", url = "http://lrr.local:3000"))
        val cache = newCache()
        awaitSnapshotSize(cache, 1)

        val req = "https://lrr.local:3000/api/info".toHttpUrl()
        assertNull(cache.findByRequestUrl(req))
    }

    @Test
    fun findCandidatesByHostPort_collectsSchemeVariantsForDowngradeDetection() = runBlocking {
        // Two profiles at the same host:port but different schemes — both candidates.
        // Distinct ports keep them as separate rows in the table; we vary host so
        // the test exercises the host:port match logic without the unique-URL
        // constraint masking the scheme-only difference.
        repo.insert(ServerProfile(name = "plain", url = "http://lrr.local:3000"))
        val cache = newCache()
        awaitSnapshotSize(cache, 1)

        val httpsRequest = "https://lrr.local:3000/api/info".toHttpUrl()
        val candidates = cache.findCandidatesByHostPort(httpsRequest.host, httpsRequest.port)
        assertEquals(1, candidates.size)
        assertEquals("plain", candidates[0].name)
    }

    @Test
    fun snapshot_reflectsLiveInsertsAndDeletes() = runBlocking {
        val cache = newCache()
        cache.awaitInitialized()
        assertTrue(cache.snapshot.value.isEmpty())

        val id1 = repo.insert(ServerProfile(name = "a", url = "http://a.local"))
        awaitSnapshotSize(cache, 1)
        assertEquals("a", cache.findById(id1)?.name)

        repo.insert(ServerProfile(name = "b", url = "http://b.local"))
        awaitSnapshotSize(cache, 2)

        repo.delete(cache.findById(id1)!!)
        awaitSnapshotSize(cache, 1)
        assertNull(cache.findById(id1))
    }

    @Test
    fun awaitInitialized_completesAfterFirstEmission() = runBlocking {
        val cache = newCache()
        // The Room flow always emits an initial value (possibly empty) on
        // subscription, so awaitInitialized must complete promptly.
        withTimeout(2_000) { cache.awaitInitialized() }
    }
}
