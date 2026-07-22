package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v27 -> v28 (MIGRATION_27_28): introduce the
 * SEARCH_HISTORY table (composite PK (QUERY, SERVER_PROFILE_ID) + recency
 * index), following the two-check harness of [RoomMigrationV26V27Test]:
 * an isolated migration-SQL check plus the gold-standard file-backed real
 * upgrade so Room's own validateMigration runs (the v1.15.1 lesson).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV27V28Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
    }

    private fun primaryKeyColumns(table: String): List<String> {
        val cols = sortedMapOf<Int, String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val pkIdx = c.getColumnIndexOrThrow("pk")
            while (c.moveToNext()) {
                val pkPos = c.getInt(pkIdx)
                if (pkPos > 0) cols[pkPos] = c.getString(nameIdx)
            }
        }
        return cols.values.toList()
    }

    @Test
    fun migration_createsSearchHistory_withCompositePkAndIndex() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // migration only ADDs a table; no v27 tables needed here
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        AppDatabase.MIGRATION_27_28.migrate(db)

        assertEquals(listOf("QUERY", "SERVER_PROFILE_ID"), primaryKeyColumns("SEARCH_HISTORY"))

        // same query may coexist for two profiles
        db.execSQL("INSERT INTO SEARCH_HISTORY (QUERY, SERVER_PROFILE_ID, LAST_USED) VALUES ('touhou', 1, 100)")
        db.execSQL("INSERT INTO SEARCH_HISTORY (QUERY, SERVER_PROFILE_ID, LAST_USED) VALUES ('touhou', 2, 200)")
        db.query("SELECT COUNT(*) FROM SEARCH_HISTORY WHERE QUERY='touhou'").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }

        val idx = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='SEARCH_HISTORY'").use {
            while (it.moveToNext()) idx.add(it.getString(0))
        }
        assertTrue(
            "missing recency index",
            "index_SEARCH_HISTORY_SERVER_PROFILE_ID_LAST_USED" in idx
        )
    }

    @Test
    fun room_opensCleanly_afterRealUpgrade_v27_to_v28() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "db_spike_v27.db"
        ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }

        // 1) Raw v27 DB FILE with ALL FIVE v27 tables (Room validates every
        //    table). createSql matches app/schemas/.../27.json.
        val v27Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_LABELS` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `LABEL` TEXT, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_LABELS_TIME` ON `DOWNLOAD_LABELS` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_DIRNAME` (`ARCID` TEXT NOT NULL, `DIRNAME` TEXT, PRIMARY KEY(`ARCID`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `QUICK_SEARCH` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `NAME` TEXT, `MODE` INTEGER NOT NULL, `CATEGORY` INTEGER NOT NULL, `KEYWORD` TEXT, `ADVANCE_SEARCH` INTEGER NOT NULL, `MIN_RATING` INTEGER NOT NULL, `PAGE_FROM` INTEGER NOT NULL, `PAGE_TO` INTEGER NOT NULL, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_QUICK_SEARCH_TIME` ON `QUICK_SEARCH` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `SERVER_PROFILES` (`ID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `NAME` TEXT NOT NULL, `URL` TEXT NOT NULL, `IS_ACTIVE` INTEGER NOT NULL, `ALLOW_CLEARTEXT` INTEGER NOT NULL DEFAULT 1)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `ARCHIVE_LOCAL_STATE` (" +
                                "`ARCID` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, " +
                                "`ARCHIVE_JSON` TEXT NOT NULL, `DOWNLOAD_STATE` INTEGER, " +
                                "`DOWNLOAD_LEGACY` INTEGER NOT NULL DEFAULT 0, `DOWNLOAD_TIME` INTEGER, " +
                                "`DOWNLOAD_LABEL` TEXT, `DOWNLOAD_ARCHIVE_URI` TEXT, `DOWNLOAD_ROOT_URI` TEXT, " +
                                "`HISTORY_TIME` INTEGER, `HISTORY_MODE` INTEGER NOT NULL DEFAULT 0, " +
                                "`HISTORY_SCROLL_FRACTION` REAL, `FAVORITE_TIME` INTEGER, " +
                                "PRIMARY KEY(`ARCID`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID` ON `ARCHIVE_LOCAL_STATE` (`SERVER_PROFILE_ID`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_HISTORY_TIME` ON `ARCHIVE_LOCAL_STATE` (`HISTORY_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME` ON `ARCHIVE_LOCAL_STATE` (`FAVORITE_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_LABEL`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build()
        )
        v27Helper.writableDatabase
        v27Helper.close()

        // 2) Open via Room at v28 -> runs MIGRATION_27_28 + validateMigration.
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_27_28)
            .build()
        try {
            room.openHelper.writableDatabase

            runBlocking {
                room.browsingDao().upsertSearchHistory(
                    SearchHistoryEntry(query = "post-upgrade", serverProfileId = 5L, lastUsed = 42L)
                )
                val rows = room.browsingDao().getRecentSearchHistory(5L, 10)
                assertEquals(listOf("post-upgrade"), rows.map { it.query })
            }
        } finally {
            room.close()
            ctx.getDatabasePath(name).delete()
        }
    }
}
