package com.lanraragi.reader.ui.scene.history

import com.lanraragi.reader.awaitViewModelIdle
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.module.CoroutineModule
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.server.SecureCredentialStore
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [HistoryViewModel] — history list loading, delete,
 * clear, snapshot reset, and DiffUtil integration.
 *
 * Uses Robolectric for Android Context + in-memory Room database.
 * [Dispatchers.Unconfined] is set as Main so that viewModelScope.launch
 * runs eagerly. Room executors are synchronous via setQueryExecutor.
 * After each async operation we idle the looper to drain any pending posts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HistoryViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        context = ApplicationProvider.getApplicationContext()

        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())

        SecureCredentialStore.initialize(context)
        // Initialize DB early so we can wire HistoryRepository before ViewModel creation
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        // Provide a DataModule with HistoryRepository so the ViewModel can resolve it
        ServiceRegistry.initializeForTest(
            data = object : IDataModule {
                override val searchHistoryRepository get() = throw UnsupportedOperationException("Not needed for these tests")
                override val historyRepository get() = HistoryRepository(db.archiveLocalStateDao(), db)
                override val profileRepository get() = ProfileRepository(db.miscDao())
                override val profileLookupCache get() = throw UnsupportedOperationException("not needed")
                override val quickSearchRepository get() = throw UnsupportedOperationException("not needed")
                override val favoritesRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadDbRepository get() = throw UnsupportedOperationException("not needed")
                override val downloadManager get() = throw UnsupportedOperationException("not needed")
                override val favouriteStatusRouter get() = throw UnsupportedOperationException("not needed")
                override val archiveDetailCache get() = throw UnsupportedOperationException("not needed")
                override fun clearArchiveDetailCache() {}
            }
        )
        val method = SecureCredentialStore::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(
            null,
            context.getSharedPreferences("lrr_auth_hist_test", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        SecureCredentialStore.clear()
    }

    // ═══════════════════════════════════════════════════════════
    // B. Load history
    // ═══════════════════════════════════════════════════════════

    @Test
    fun loadHistory_populatesHistoryList() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)

        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun loadHistory_startsLoading_andNoHistoryIsEmpty() {
        val vm = HistoryViewModel()
        assertEquals(ListScreenState.Loading, vm.state.value)

        vm.loadHistory()
        awaitViewModelIdle(vm)

        assertEquals(ListScreenState.Empty, vm.state.value)
    }

    /** A reload over shown rows keeps them (no blank list while it runs). */
    @Test
    fun loadHistory_overRows_staysContent() {
        insertGalleries(1L to "Gallery One")
        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)

        vm.loadHistory()

        assertTrue(vm.state.value is ListScreenState.Content)
        awaitViewModelIdle(vm)
        assertEquals(listOf("Gallery One"), vm.state.value.items.map { it.archive.title })
    }

    /** Each row carries its own Room entity: no second list matched by index. */
    @Test
    fun rows_pairTheEntityWithTheArchiveItShows() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")
        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)

        for (row in vm.state.value.items) assertEquals(row.info.arcid, row.archive.arcid)
    }

    /** A rating saved on the detail page changes the shown row at once and survives a reload. */
    @Test
    fun updateRating_changesTheRow_andIsPersisted() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")
        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)

        vm.updateRating("tok2", 4.5f)
        assertEquals(4.5f, vm.state.value.items.single { it.archive.arcid == "tok2" }.archive.rating)

        awaitViewModelIdle(vm)
        vm.loadHistory()
        awaitViewModelIdle(vm)
        assertEquals(4.5f, vm.state.value.items.single { it.archive.arcid == "tok2" }.archive.rating)
    }

    // ═══════════════════════════════════════════════════════════
    // C. Delete operations
    // ═══════════════════════════════════════════════════════════

    @Test
    fun deleteHistoryItem_removesItemAndReloads() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)
        assertEquals(2, vm.state.value.items.size)

        val itemToDelete = vm.state.value.items[0].info
        vm.deleteHistoryItem(itemToDelete)
        awaitViewModelIdle(vm)

        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun clearAllHistory_emptiesTheList() {
        insertGalleries(1L to "Gallery One", 2L to "Gallery Two")

        val vm = HistoryViewModel()
        vm.loadHistory()
        awaitViewModelIdle(vm)
        assertEquals(2, vm.state.value.items.size)

        vm.clearAllHistory()
        awaitViewModelIdle(vm)

        assertTrue(vm.state.value.items.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Inserts galleries into the history table via the DAO directly.
     */
    private fun insertGalleries(vararg galleries: Pair<Long, String>) {
        runBlocking {
            val repo = ServiceRegistry.dataModule.historyRepository
            for ((gid, title) in galleries) {
                val archive = com.lanraragi.reader.domain.Archive(
                    arcid = "tok$gid",
                    title = title,
                    tags = emptyMap(),
                    pagecount = 0,
                    progress = 0,
                    extension = "",
                    filename = "",
                    thumbnailUrl = "",
                    rating = 0f,
                    isnew = false,
                    lastreadtime = 0L,
                    summary = null,
                    serverProfileId = 0L,
                )
                repo.putHistoryInfo(archive)
            }
        }
    }
}
