package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for [MiscRoomDao] server profile operations.
 * Uses Robolectric + in-memory Room database (no emulator needed).
 *
 * @Config(application = ...) is NOT set to avoid loading the real Application
 * class (EhApplication) which loads native libraries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = android.app.Application::class  // bypass EhApplication
)
class ServerProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MiscRoomDao

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.miscDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndQuery() = runTest {
        val profile = ServerProfile(name = "Test", url = "https://test.com")
        val id = dao.insertServerProfile(profile)
        assertTrue(id > 0)

        val all = dao.getAllServerProfiles()
        assertEquals(1, all.size)
        assertEquals("Test", all[0].name)
        assertEquals("https://test.com", all[0].url)
    }

    @Test
    fun updateProfile() = runTest {
        val profile = ServerProfile(name = "Old", url = "https://old.com")
        val id = dao.insertServerProfile(profile)

        val updated = ServerProfile(id = id, name = "New", url = "https://new.com")
        dao.updateServerProfile(updated)

        val all = dao.getAllServerProfiles()
        assertEquals(1, all.size)
        assertEquals("New", all[0].name)
        assertEquals("https://new.com", all[0].url)
    }

    @Test
    fun deleteProfile() = runTest {
        val profile = ServerProfile(name = "Delete Me", url = "https://delete.com")
        val id = dao.insertServerProfile(profile)

        val toDelete = ServerProfile(id = id, name = "Delete Me", url = "https://delete.com")
        dao.deleteServerProfile(toDelete)

        val all = dao.getAllServerProfiles()
        assertTrue(all.isEmpty())
    }

    @Test
    fun deactivateAll() = runTest {
        dao.insertServerProfile(ServerProfile(name = "A", url = "https://a.com", isActive = true))
        dao.insertServerProfile(ServerProfile(name = "B", url = "https://b.com", isActive = true))

        dao.deactivateAllProfiles()

        val all = dao.getAllServerProfiles()
        assertEquals(2, all.size)
        assertTrue(all.all { !it.isActive })
    }

    @Test
    fun getActiveProfile() = runTest {
        dao.insertServerProfile(ServerProfile(name = "Inactive", url = "https://inactive.com", isActive = false))
        dao.insertServerProfile(ServerProfile(name = "Active", url = "https://active.com", isActive = true))

        val active = dao.getActiveProfile()
        assertNotNull(active)
        assertEquals("Active", active!!.name)
        assertTrue(active.isActive)
    }

    @Test
    fun getActiveProfile_none() = runTest {
        dao.insertServerProfile(ServerProfile(name = "Inactive", url = "https://inactive.com", isActive = false))

        val active = dao.getActiveProfile()
        assertNull(active)
    }

    @Test
    fun findProfileByUrl() = runTest {
        dao.insertServerProfile(ServerProfile(name = "Found", url = "https://found.com"))
        dao.insertServerProfile(ServerProfile(name = "Other", url = "https://other.com"))

        val found = dao.findProfileByUrl("https://found.com")
        assertNotNull(found)
        assertEquals("Found", found!!.name)

        val notFound = dao.findProfileByUrl("https://missing.com")
        assertNull(notFound)
    }

    @Test
    fun autoGenerateId() = runTest {
        val id1 = dao.insertServerProfile(ServerProfile(name = "A", url = "https://a.com"))
        val id2 = dao.insertServerProfile(ServerProfile(name = "B", url = "https://b.com"))

        assertTrue(id1 > 0)
        assertTrue(id2 > 0)
        assertNotEquals(id1, id2)
    }
}
