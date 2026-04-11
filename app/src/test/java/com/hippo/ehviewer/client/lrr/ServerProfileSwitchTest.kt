package com.lanraragi.reader.client.api

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.ServerProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for server profile switching logic:
 * - Profile CRUD + active flag management
 * - Per-profile API key isolation via LRRAuthManager
 * - Active profile persistence round-trip
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ServerProfileSwitchTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        LRRAuthManager.initialize(ctx)
        // In Robolectric, EncryptedSharedPreferences always fails — inject plain prefs
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("profile_switch_test", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        db.close()
        LRRAuthManager.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // Profile active flag management
    // ═══════════════════════════════════════════════════════════

    @Test
    fun switchProfile_deactivatesOldAndActivatesNew() = runTest {
        val dao = db.miscDao()

        val id1 = dao.insertServerProfile(
            ServerProfile(name = "Server A", url = "http://192.168.1.10:3000", isActive = true)
        )
        val id2 = dao.insertServerProfile(
            ServerProfile(name = "Server B", url = "http://192.168.1.20:3000", isActive = false)
        )

        // Switch to Server B
        dao.deactivateAllProfiles()
        dao.updateServerProfile(ServerProfile(id2, "Server B", "http://192.168.1.20:3000", true))

        val profiles = dao.getAllServerProfiles()
        val a = profiles.first { it.id == id1 }
        val b = profiles.first { it.id == id2 }
        assertFalse("Server A should be inactive", a.isActive)
        assertTrue("Server B should be active", b.isActive)
    }

    @Test
    fun getActiveProfile_returnsOnlyActiveOne() = runTest {
        val dao = db.miscDao()

        dao.insertServerProfile(ServerProfile(name = "Inactive", url = "http://a:3000", isActive = false))
        dao.insertServerProfile(ServerProfile(name = "Active", url = "http://b:3000", isActive = true))
        dao.insertServerProfile(ServerProfile(name = "Also Inactive", url = "http://c:3000", isActive = false))

        val active = dao.getActiveProfile()
        assertNotNull(active)
        assertEquals("Active", active!!.name)
    }

    @Test
    fun getActiveProfile_returnsNullWhenNoneActive() = runTest {
        val dao = db.miscDao()
        dao.insertServerProfile(ServerProfile(name = "X", url = "http://x:3000", isActive = false))

        assertNull(dao.getActiveProfile())
    }

    // ═══════════════════════════════════════════════════════════
    // Per-profile API key isolation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun apiKeyPerProfile_isolatedBetweenProfiles() {
        LRRAuthManager.setApiKeyForProfile(1L, "key_for_server_1")
        LRRAuthManager.setApiKeyForProfile(2L, "key_for_server_2")

        assertEquals("key_for_server_1", LRRAuthManager.getApiKeyForProfile(1L))
        assertEquals("key_for_server_2", LRRAuthManager.getApiKeyForProfile(2L))
    }

    @Test
    fun apiKeyPerProfile_clearRemovesOnlyTarget() {
        LRRAuthManager.setApiKeyForProfile(10L, "keep_me")
        LRRAuthManager.setApiKeyForProfile(20L, "delete_me")

        LRRAuthManager.clearApiKeyForProfile(20L)

        assertEquals("keep_me", LRRAuthManager.getApiKeyForProfile(10L))
        assertNull(LRRAuthManager.getApiKeyForProfile(20L))
    }

    @Test
    fun apiKeyPerProfile_emptyStringClearsKey() {
        LRRAuthManager.setApiKeyForProfile(5L, "some_key")
        LRRAuthManager.setApiKeyForProfile(5L, "")

        assertNull(LRRAuthManager.getApiKeyForProfile(5L))
    }

    // ═══════════════════════════════════════════════════════════
    // Active profile ID persistence
    // ═══════════════════════════════════════════════════════════

    @Test
    fun activeProfileId_persistsAndRestores() {
        LRRAuthManager.setActiveProfileId(42L)
        assertEquals(42L, LRRAuthManager.getActiveProfileId())
    }

    @Test
    fun activeProfileId_defaultsToZero() {
        // After clear, should default to 0
        LRRAuthManager.clear()
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
    }

    // ═══════════════════════════════════════════════════════════
    // Full switch simulation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun fullSwitch_updatesAllAuthState() = runTest {
        val dao = db.miscDao()

        // Set up two profiles
        val id1 = dao.insertServerProfile(
            ServerProfile(name = "Home", url = "http://home:3000", isActive = true)
        )
        LRRAuthManager.setApiKeyForProfile(id1, "home_key")
        LRRAuthManager.setServerUrl("http://home:3000")
        LRRAuthManager.setApiKey("home_key")
        LRRAuthManager.setActiveProfileId(id1)

        val id2 = dao.insertServerProfile(
            ServerProfile(name = "Office", url = "https://office.lan:3000", isActive = false)
        )
        LRRAuthManager.setApiKeyForProfile(id2, "office_key")

        // Simulate switch to Office
        dao.deactivateAllProfiles()
        dao.updateServerProfile(ServerProfile(id2, "Office", "https://office.lan:3000", true))
        LRRAuthManager.setServerUrl("https://office.lan:3000")
        LRRAuthManager.setApiKey(LRRAuthManager.getApiKeyForProfile(id2))
        LRRAuthManager.setActiveProfileId(id2)

        // Verify all state updated
        assertEquals("https://office.lan:3000", LRRAuthManager.getServerUrl())
        assertEquals("office_key", LRRAuthManager.getApiKey())
        assertEquals(id2, LRRAuthManager.getActiveProfileId())
        assertTrue(dao.getActiveProfile()!!.isActive)
        assertEquals("Office", dao.getActiveProfile()!!.name)

        // Verify old profile keys still intact
        assertEquals("home_key", LRRAuthManager.getApiKeyForProfile(id1))
    }
}
