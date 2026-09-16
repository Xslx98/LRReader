package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.data.LRRCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour tests for [QuickSearchRepository.syncCategoryNames] against a
 * real in-memory Room DB (spec 2026-09-15, Q4): rows migrated from the
 * legacy keyword protocol carry a NULL CATEGORY_NAME until the next
 * successful categories fetch; the sync fills them by id, follows
 * server-side renames, ignores everything else and is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class QuickSearchRepositoryCategoryNameTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: QuickSearchRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = QuickSearchRepository(db.browsingDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun category(id: String?, name: String?) =
        LRRCategory().also { it.id = id; it.name = name }

    private suspend fun seed(name: String, categoryId: String?, categoryName: String?, keyword: String? = null) {
        repo.insert(
            QuickSearch(
                name = name,
                categoryId = categoryId,
                categoryName = categoryName,
                keyword = keyword,
                time = 1
            )
        )
    }

    private suspend fun nameOf(quickSearchName: String): String? =
        repo.getAll().single { it.name == quickSearchName }.categoryName

    @Test
    fun fillsNullNamesById() = runBlocking {
        seed("migrated", "SET_1", null)

        val changed = repo.syncCategoryNames(listOf(category("SET_1", "Favourites")))

        assertEquals(1, changed)
        assertEquals("Favourites", nameOf("migrated"))
    }

    @Test
    fun refreshesAChangedName() = runBlocking {
        seed("saved", "SET_1", "Old name")

        val changed = repo.syncCategoryNames(listOf(category("SET_1", "New name")))

        assertEquals(1, changed)
        assertEquals("New name", nameOf("saved"))
    }

    @Test
    fun leavesOtherIdsAndPlainKeywordRowsUntouched() = runBlocking {
        seed("other", "SET_2", null)
        seed("plain", null, null, keyword = "touhou")

        val changed = repo.syncCategoryNames(listOf(category("SET_1", "Favourites")))

        assertEquals(0, changed)
        assertNull(nameOf("other"))
        assertNull(nameOf("plain"))
        assertNull(repo.getAll().single { it.name == "plain" }.categoryId)
    }

    @Test
    fun secondIdenticalCallUpdatesNothing() = runBlocking {
        seed("migrated", "SET_1", null)
        val categories = listOf(category("SET_1", "Favourites"))

        repo.syncCategoryNames(categories)
        val second = repo.syncCategoryNames(categories)

        assertEquals(0, second)
        assertEquals("Favourites", nameOf("migrated"))
    }

    @Test
    fun categoriesWithoutIdOrNameAreSkipped() = runBlocking {
        seed("migrated", "SET_1", null)

        val changed = repo.syncCategoryNames(
            listOf(category(null, "No id"), category("SET_1", null), category("SET_1", "  "))
        )

        assertEquals(0, changed)
        assertNull(nameOf("migrated"))
    }
}
