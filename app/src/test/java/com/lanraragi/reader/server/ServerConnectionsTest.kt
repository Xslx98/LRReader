package com.lanraragi.reader.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.stubNetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ServerConnectionsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProfileRepository
    private lateinit var scope: CoroutineScope
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProfileRepository(db.miscDao())
        scope = containedTestScope()
        val prefs = ctx.getSharedPreferences("lrr_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        LRRAuthManager.initializeForTesting(prefs)
        ServiceRegistry.initializeForTest(network = stubNetworkModule(client))
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun active_isNullWithoutAServer() {
        assertNull(ServerConnections.active())
    }

    @Test
    fun active_isTheActiveProfileOnTheSharedClient() {
        LRRAuthManager.setServerUrl("http://active.local:3000")
        LRRAuthManager.setActiveProfileId(7L)

        val conn = ServerConnections.active()!!

        assertEquals(7L, conn.profileId)
        assertEquals("http://active.local:3000", conn.baseUrl)
        assertSame(client, conn.client)
    }

    @Test
    fun forProfile_isTheSourceProfileNotTheActiveOne() = runBlocking {
        LRRAuthManager.setServerUrl("http://active.local:3000")
        val id = repo.insert(ServerProfile(name = "B", url = "http://server-b.local:3000"))
        val cache = ProfileLookupCache(repo, scope)
        withTimeout(2_000) { while (cache.snapshot.value.size != 1) kotlinx.coroutines.delay(20) }

        val conn = ServerConnections.forProfile(id, cache)

        assertEquals(id, conn.profileId)
        assertEquals("http://server-b.local:3000", conn.baseUrl)
        assertSame(client, conn.client)
    }

    @Test
    fun forProfile_unknownProfileThrowsOrphan() {
        val cache = ProfileLookupCache(repo, scope)
        val ex = assertThrows(OrphanProfileException::class.java) {
            runBlocking {
                cache.awaitInitialized()
                ServerConnections.forProfile(9999L, cache)
            }
        }
        assertEquals(9999L, ex.profileId)
    }
}
