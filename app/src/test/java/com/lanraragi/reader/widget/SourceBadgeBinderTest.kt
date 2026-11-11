package com.lanraragi.reader.widget

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.MiscRoomDao
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.server.ProfileLookupCache
import com.lanraragi.reader.stubDataModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #104 follow-up: the source badge (downloads list, detail header) is bound
 * from [ProfileLookupCache], whose snapshot is unknown until Room's first
 * emission. A badge bound that early must not read "source deleted", and the
 * re-bind on each snapshot emission (what the screens' collectors do) must
 * show the profile once it loads and its new name after a rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SourceBadgeBinderTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var cache: ProfileLookupCache
    private lateinit var badge: TextView

    /** Stands in for Room's profile flow so the test decides when it first emits. */
    private val profiles = MutableStateFlow<List<ServerProfile>?>(null)

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = containedTestScope()
        val gatedDao = object : MiscRoomDao by db.miscDao() {
            override fun observeAllProfiles(): Flow<List<ServerProfile>> = profiles.filterNotNull()
        }
        cache = ProfileLookupCache(ProfileRepository(gatedDao), scope)
        ServiceRegistry.initializeForTest(data = object : IDataModule by stubDataModule() {
            override val profileLookupCache get() = cache
        })
        badge = TextView(ContextThemeWrapper(ctx, R.style.AppTheme))
    }

    @After
    fun tearDown() {
        ServiceRegistry.initializeForTest(data = stubDataModule())
        scope.cancel()
        db.close()
    }

    /** Emits [list] from the fake Room flow and waits until the cache's snapshot carries it. */
    private fun publish(vararg list: ServerProfile) = runBlocking {
        profiles.value = list.toList()
        withTimeout(2_000) { cache.snapshot.first { it == list.toList() } }
    }

    private val deletedText get() = badge.context.getString(R.string.lrr_download_source_deleted)

    @Test(timeout = 10_000)
    fun aBadgeBoundBeforeTheProfilesLoad_isHidden_notDeleted() {
        val name = bindSourceServerBadge(badge, 2L)

        assertNull(name)
        assertEquals(View.GONE, badge.visibility)
        assertNotEquals(deletedText, badge.text.toString())
    }

    @Test(timeout = 10_000)
    fun reboundOnSnapshotChanges_showsTheProfile_thenItsNewName() {
        bindSourceServerBadge(badge, 2L)

        publish(ServerProfile(id = 2L, name = "Server B", url = "http://192.168.1.2:3000"))
        bindSourceServerBadge(badge, 2L)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals("Server B", badge.text.toString())

        publish(ServerProfile(id = 2L, name = "Renamed", url = "http://192.168.1.2:3000"))
        bindSourceServerBadge(badge, 2L)
        assertEquals("Renamed", badge.text.toString())
    }

    @Test(timeout = 10_000)
    fun aProfileMissingFromALoadedSnapshot_readsDeleted() {
        publish(ServerProfile(id = 1L, name = "Server A", url = "http://192.168.1.1:3000"))

        bindSourceServerBadge(badge, 2L)

        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals(deletedText, badge.text.toString())
    }
}
