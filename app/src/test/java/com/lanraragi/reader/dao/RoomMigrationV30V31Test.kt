package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v30 -> v31 (MIGRATION_30_31): the
 * `CATEGORY_ID` / `CATEGORY_NAME` columns on QUICK_SEARCH plus the one-time
 * rewrite of the legacy `"category:<id>"` keyword protocol (spec
 * 2026-09-15, Q3/Q4) and the purge of SEARCH_HISTORY rows that captured
 * that text. Follows the established two-check harness (isolated SQL +
 * file-backed real upgrade with Room validateMigration).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV30V31Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
    }

    @Test
    fun migration_addsCategoryColumns_andRewritesLegacyRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Only the two mutated tables matter for the isolated check.
                    db.execSQL(QUICK_SEARCH_V30_DDL)
                    db.execSQL(SEARCH_HISTORY_DDL)
                    insertQuickSearch(db, "qs1", "category:SET_1")
                    insertQuickSearch(db, "qs2", "touhou")
                    insertQuickSearch(db, "qs3", "Category:SET_2") // capital C: not the protocol
                    insertQuickSearch(db, "qs4", "category:") // degenerate: no id
                    insertQuickSearch(db, "qs5", null)
                    db.execSQL(
                        "INSERT INTO SEARCH_HISTORY (QUERY, SERVER_PROFILE_ID, LAST_USED) VALUES " +
                            "('category:SET_1', 1, 10), ('touhou', 1, 11), ('category:SET_1', 2, 12)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        AppDatabase.MIGRATION_30_31.migrate(db)

        // Columns exist.
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(QUICK_SEARCH)").use {
            while (it.moveToNext()) columns.add(it.getString(it.getColumnIndexOrThrow("name")))
        }
        assertTrue(columns.contains("CATEGORY_ID"))
        assertTrue(columns.contains("CATEGORY_NAME"))

        // Legacy protocol row rewritten: id column filled, keyword cleared, name left NULL.
        assertQuickSearch(db, "qs1", keyword = null, categoryId = "SET_1", categoryName = null)
        // Plain keyword untouched.
        assertQuickSearch(db, "qs2", keyword = "touhou", categoryId = null, categoryName = null)
        // Case-exact protocol: "Category:" is plain text.
        assertQuickSearch(db, "qs3", keyword = "Category:SET_2", categoryId = null, categoryName = null)
        // Degenerate "category:" without an id stays plain text.
        assertQuickSearch(db, "qs4", keyword = "category:", categoryId = null, categoryName = null)
        // NULL keyword survives.
        assertQuickSearch(db, "qs5", keyword = null, categoryId = null, categoryName = null)

        // History: protocol rows deleted across every profile, plain rows kept.
        db.query("SELECT QUERY, SERVER_PROFILE_ID FROM SEARCH_HISTORY ORDER BY LAST_USED").use {
            assertTrue(it.moveToFirst())
            assertEquals("touhou", it.getString(0))
            assertEquals(1L, it.getLong(1))
            assertFalse(it.moveToNext())
        }
    }

    @Test
    fun room_opensCleanly_afterRealUpgrade_v30_to_v31() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "db_spike_v30.db"
        ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }

        // Raw v30 DB FILE with ALL EIGHT v30 tables (Room validates every table).
        val v30Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_LABELS` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `LABEL` TEXT, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_LABELS_TIME` ON `DOWNLOAD_LABELS` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_DIRNAME` (`ARCID` TEXT NOT NULL, `DIRNAME` TEXT, PRIMARY KEY(`ARCID`))")
                        db.execSQL(QUICK_SEARCH_V30_DDL)
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
                                "`DOWNLOAD_TANK_ID` TEXT, " +
                                "PRIMARY KEY(`ARCID`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID` ON `ARCHIVE_LOCAL_STATE` (`SERVER_PROFILE_ID`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_HISTORY_TIME` ON `ARCHIVE_LOCAL_STATE` (`HISTORY_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME` ON `ARCHIVE_LOCAL_STATE` (`FAVORITE_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_LABEL`)")
                        db.execSQL(SEARCH_HISTORY_DDL)
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_SEARCH_HISTORY_SERVER_PROFILE_ID_LAST_USED` ON `SEARCH_HISTORY` (`SERVER_PROFILE_ID`, `LAST_USED`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `DAILY_READING_AGGREGATE` (" +
                                "`EPOCH_DAY` INTEGER NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL, " +
                                "`PAGES_READ` INTEGER NOT NULL, `COMPLETED` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`EPOCH_DAY`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "`index_DAILY_READING_AGGREGATE_SERVER_PROFILE_ID_EPOCH_DAY` " +
                                "ON `DAILY_READING_AGGREGATE` (`SERVER_PROFILE_ID`, `EPOCH_DAY`)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `TANK_DOWNLOAD_GROUP` (" +
                                "`TANK_ID` TEXT NOT NULL, " +
                                "`SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, " +
                                "`NAME` TEXT NOT NULL, " +
                                "`MEMBER_IDS_JSON` TEXT NOT NULL, " +
                                "`CREATED_TIME` INTEGER NOT NULL DEFAULT 0, " +
                                "PRIMARY KEY(`TANK_ID`))"
                        )
                        insertQuickSearch(db, "legacy", "category:SET_1")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build()
        )
        v30Helper.writableDatabase
        v30Helper.close()

        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_30_31)
            .build()
        try {
            room.openHelper.writableDatabase

            runBlocking {
                val dao = room.browsingDao()
                val migrated = dao.getAllQuickSearch().single()
                assertEquals("legacy", migrated.name)
                assertEquals("SET_1", migrated.categoryId)
                assertNull(migrated.categoryName)
                assertNull(migrated.keyword)

                dao.insertQuickSearch(
                    QuickSearch(name = "nine", categoryId = "SET_9", categoryName = "Nine", time = 99)
                )
                val all = dao.getAllQuickSearch()
                assertEquals(2, all.size)
                val nine = all.single { it.name == "nine" }
                assertEquals("SET_9", nine.categoryId)
                assertEquals("Nine", nine.categoryName)
            }
        } finally {
            room.close()
            ctx.getDatabasePath(name).delete()
        }
    }

    private fun assertQuickSearch(
        db: SupportSQLiteDatabase,
        name: String,
        keyword: String?,
        categoryId: String?,
        categoryName: String?
    ) {
        db.query("SELECT KEYWORD, CATEGORY_ID, CATEGORY_NAME FROM QUICK_SEARCH WHERE NAME='$name'").use {
            assertTrue("row $name missing", it.moveToFirst())
            assertEquals("keyword of $name", keyword, it.getStringOrNull(0))
            assertEquals("categoryId of $name", categoryId, it.getStringOrNull(1))
            assertEquals("categoryName of $name", categoryName, it.getStringOrNull(2))
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private companion object {
        const val QUICK_SEARCH_V30_DDL =
            "CREATE TABLE IF NOT EXISTS `QUICK_SEARCH` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`NAME` TEXT, `MODE` INTEGER NOT NULL, `CATEGORY` INTEGER NOT NULL, `KEYWORD` TEXT, " +
                "`ADVANCE_SEARCH` INTEGER NOT NULL, `MIN_RATING` INTEGER NOT NULL, " +
                "`PAGE_FROM` INTEGER NOT NULL, `PAGE_TO` INTEGER NOT NULL, `TIME` INTEGER NOT NULL)"

        const val SEARCH_HISTORY_DDL =
            "CREATE TABLE IF NOT EXISTS `SEARCH_HISTORY` (" +
                "`QUERY` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL, " +
                "`LAST_USED` INTEGER NOT NULL, " +
                "PRIMARY KEY(`QUERY`, `SERVER_PROFILE_ID`))"

        fun insertQuickSearch(db: SupportSQLiteDatabase, name: String, keyword: String?) {
            db.execSQL(
                "INSERT INTO QUICK_SEARCH (NAME, MODE, CATEGORY, KEYWORD, ADVANCE_SEARCH, MIN_RATING, " +
                    "PAGE_FROM, PAGE_TO, TIME) VALUES (?, 0, -1, ?, -1, -1, -1, -1, 1)",
                arrayOf<Any?>(name, keyword)
            )
        }
    }
}
