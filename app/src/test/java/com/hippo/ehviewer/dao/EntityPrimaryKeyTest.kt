package com.hippo.ehviewer.dao

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for D14 — Entity @Entity(primaryKeys=...) must match the actual
 * Room-built schema, and every DOWNLOADS/HISTORY/LOCAL_FAVORITES table must accept
 * multiple rows on a clean install.
 *
 * Background: MIGRATION_19_20 correctly created tables with PRIMARY KEY (ARCID),
 * but the Kotlin Entity annotations still declared primaryKeys = ["GID"]. Existing
 * users migrated via SQL were fine, but clean installs at v20 built the schema from
 * the (wrong) annotations — every row had gid=0L after W33, so only ONE row could
 * ever exist per table. MIGRATION_20_21 (cd31daaf) fixed the annotations and rebuilt
 * the affected installs.
 *
 * These tests ensure the bug cannot recur:
 *  1. Annotation-derived PK (via clean-install PRAGMA) must be ARCID, not GID.
 *  2. Multiple rows with distinct arcid (and default gid=0) must coexist per table.
 *
 * Run with: ./gradlew testAppReleaseDebugUnitTest --tests "*.EntityPrimaryKeyTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = android.app.Application::class // bypass EhApplication native libs
)
class EntityPrimaryKeyTest {

    private lateinit var db: AppDatabase
    private lateinit var sqliteDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sqliteDb = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- PRAGMA-based PK checks (annotation vs runtime schema) ----------

    @Test
    fun `DOWNLOADS primary key column is ARCID`() {
        assertEquals(setOf("ARCID"), primaryKeyColumnsOf("DOWNLOADS"))
    }

    @Test
    fun `HISTORY primary key column is ARCID`() {
        assertEquals(setOf("ARCID"), primaryKeyColumnsOf("HISTORY"))
    }

    @Test
    fun `LOCAL_FAVORITES primary key column is ARCID`() {
        assertEquals(setOf("ARCID"), primaryKeyColumnsOf("LOCAL_FAVORITES"))
    }

    // ---------- Behavioral guard: clean install accepts multiple rows ----------

    @Test
    fun `DownloadDao accepts three rows with distinct arcid and default gid`() = runBlocking {
        val dao = db.downloadDao()
        val now = System.currentTimeMillis()
        repeat(3) { i ->
            val info = DownloadInfo().apply {
                // deliberately leave gid at default (0L) to mimic W33+ state
                arcid = "arcid_$i"
                title = "Title $i"
                state = DownloadInfo.STATE_NONE
                time = now + i
            }
            dao.insert(info)
        }

        val rows = dao.getAllDownloadInfo()
        assertEquals(3, rows.size)
        assertEquals(setOf("arcid_0", "arcid_1", "arcid_2"), rows.map { it.arcid }.toSet())
        assertTrue("All rows should share the default gid=0", rows.all { it.gid == 0L })
    }

    @Test
    fun `HistoryDao accepts three rows with distinct arcid and default gid`() = runBlocking {
        val dao = db.browsingDao()
        val now = System.currentTimeMillis()
        repeat(3) { i ->
            val info = HistoryInfo().apply {
                arcid = "arcid_h_$i"
                title = "History $i"
                time = now + i
            }
            dao.insertHistory(info)
        }

        val rows = dao.getAllHistory()
        assertEquals(3, rows.size)
        assertEquals(setOf("arcid_h_0", "arcid_h_1", "arcid_h_2"), rows.map { it.arcid }.toSet())
    }

    @Test
    fun `LocalFavoriteDao accepts three rows with distinct arcid and default gid`() = runBlocking {
        val dao = db.browsingDao()
        val now = System.currentTimeMillis()
        repeat(3) { i ->
            val info = LocalFavoriteInfo().apply {
                arcid = "arcid_lf_$i"
                title = "Fav $i"
                time = now + i
            }
            dao.insertLocalFavorite(info)
        }

        val rows = dao.getAllLocalFavorites()
        assertEquals(3, rows.size)
        assertEquals(setOf("arcid_lf_0", "arcid_lf_1", "arcid_lf_2"), rows.map { it.arcid }.toSet())
    }

    // ---------- Helpers ----------

    /**
     * Returns the set of column names that participate in the primary key for [tableName].
     * SQLite's PRAGMA table_info exposes a "pk" column that is non-zero for PK members
     * (the value indicates ordinal position for composite keys; 0 means not a PK member).
     */
    private fun primaryKeyColumnsOf(tableName: String): Set<String> {
        val cursor = sqliteDb.query("PRAGMA table_info(`$tableName`)")
        val pkCols = mutableSetOf<String>()
        try {
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val pkIdx = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                if (cursor.getInt(pkIdx) > 0) {
                    pkCols.add(cursor.getString(nameIdx))
                }
            }
        } finally {
            cursor.close()
        }
        return pkCols
    }
}
