package com.lanraragi.reader.server

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.MiscRoomDao
import com.lanraragi.reader.dao.ServerProfile
import kotlinx.coroutines.runBlocking
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

/**
 * [LegacyActiveSessionMigration]: an install from before v2.5 hands its
 * secure-store copy of the active server to Room once, then drops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyActiveSessionMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MiscRoomDao
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.miscDao()
        prefs = ctx.getSharedPreferences("legacy_active_session_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SecureCredentialStore.initializeForTesting(prefs)
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
        db.close()
    }

    private fun legacySession(id: Long, url: String? = "http://nas.local:3000", key: String? = "legacy-key") {
        prefs.edit()
            .putLong("active_profile_id", id)
            .apply { if (url != null) putString("server_url", url) }
            .apply { if (key != null) putString("api_key", key) }
            .putString("server_name", "NAS")
            .putBoolean("allow_cleartext", false)
            .putBoolean("server_configured", true)
            .putBoolean("was_configured", true)
            .commit()
    }

    private fun assertLegacyCopyGone() {
        for (key in listOf("server_url", "api_key", "server_name", "active_profile_id", "allow_cleartext", "server_configured", "was_configured")) {
            assertFalse("$key is dropped", prefs.contains(key))
        }
    }

    @Test
    fun noActiveRow_adoptsTheStoredProfileId() = runBlocking {
        dao.insertServerProfile(ServerProfile(1, "A", "http://a.local", false))
        dao.insertServerProfile(ServerProfile(2, "B", "http://b.local", false))
        legacySession(id = 2)

        LegacyActiveSessionMigration.run(dao)

        assertEquals(2L, dao.getActiveProfile()?.id)
        assertEquals("only the two rows", 2, dao.getAllServerProfiles().size)
        assertLegacyCopyGone()
    }

    @Test
    fun anActiveRowAlreadyExists_isKept() = runBlocking {
        dao.insertServerProfile(ServerProfile(1, "A", "http://a.local", true))
        dao.insertServerProfile(ServerProfile(2, "B", "http://b.local", false))
        legacySession(id = 2)

        LegacyActiveSessionMigration.run(dao)

        assertEquals("Room's active row wins", 1L, dao.getActiveProfile()?.id)
        assertLegacyCopyGone()
    }

    @Test
    fun serverThatNeverBecameARow_becomesTheActiveProfileWithItsKey() = runBlocking {
        legacySession(id = 0)

        LegacyActiveSessionMigration.run(dao)

        val active = dao.getActiveProfile()!!
        assertEquals("http://nas.local:3000", active.url)
        assertEquals("NAS", active.name)
        assertFalse("the stored cleartext consent is kept", active.allowCleartext)
        assertEquals("legacy-key", ServerSession.getApiKeyForProfile(active.id))
        assertLegacyCopyGone()
    }

    @Test
    fun staleIdWithOtherProfiles_activatesNothing() = runBlocking {
        dao.insertServerProfile(ServerProfile(1, "A", "http://a.local", false))
        legacySession(id = 9)

        LegacyActiveSessionMigration.run(dao)

        assertNull("a deleted profile is not resurrected", dao.getActiveProfile())
        assertEquals(1, dao.getAllServerProfiles().size)
        assertLegacyCopyGone()
    }

    @Test
    fun storeClosed_keepsTheCopyForALaterBoot() = runBlocking {
        dao.insertServerProfile(ServerProfile(2, "B", "http://b.local", false))
        legacySession(id = 2)
        SecureCredentialStore.simulateStorageUnavailableForTesting()

        LegacyActiveSessionMigration.run(dao)

        assertNull(dao.getActiveProfile())
        assertTrue("unread copies stay", prefs.contains("server_url"))
    }
}
