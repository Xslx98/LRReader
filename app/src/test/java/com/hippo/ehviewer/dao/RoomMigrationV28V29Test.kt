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
 * Migration path test for v28 -> v29 (MIGRATION_28_29): introduce the
 * DAILY_READING_AGGREGATE table (composite PK (EPOCH_DAY, SERVER_PROFILE_ID)
 * + per-profile index), following the established two-check harness
 * (isolated SQL + file-backed real upgrade with Room validateMigration).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV28V29Test {

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
    fun migration_createsDailyAggregate_withCompositePkAndIndex() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(28) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        AppDatabase.MIGRATION_28_29.migrate(db)

        assertEquals(listOf("EPOCH_DAY", "SERVER_PROFILE_ID"), primaryKeyColumns("DAILY_READING_AGGREGATE"))

        db.execSQL("INSERT INTO DAILY_READING_AGGREGATE (EPOCH_DAY, SERVER_PROFILE_ID, PAGES_READ, COMPLETED) VALUES (100, 1, 5, 0)")
        db.execSQL("INSERT INTO DAILY_READING_AGGREGATE (EPOCH_DAY, SERVER_PROFILE_ID, PAGES_READ, COMPLETED) VALUES (100, 2, 3, 1)")
        db.query("SELECT COUNT(*) FROM DAILY_READING_AGGREGATE WHERE EPOCH_DAY=100").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }

        val idx = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='DAILY_READING_AGGREGATE'").use {
            while (it.moveToNext()) idx.add(it.getString(0))
        }
        assertTrue(
            "missing per-profile index",
            "index_DAILY_READING_AGGREGATE_SERVER_PROFILE_ID_EPOCH_DAY" in idx
        )
    }

    @Test
    fun room_opensCleanly_afterRealUpgrade_v28_to_v29() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "db_spike_v28.db"
        ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }

        // Raw v28 DB FILE with ALL SIX v28 tables (Room validates every table).
        val v28Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(28) {
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
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `SEARCH_HISTORY` (" +
                                "`QUERY` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL, " +
                                "`LAST_USED` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`QUERY`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_SEARCH_HISTORY_SERVER_PROFILE_ID_LAST_USED` ON `SEARCH_HISTORY` (`SERVER_PROFILE_ID`, `LAST_USED`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build()
        )
        v28Helper.writableDatabase
        v28Helper.close()

        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_28_29)
            .build()
        try {
            room.openHelper.writableDatabase

            runBlocking {
                room.statsDao().insertDailyAggregateIfAbsent(DailyReadingAggregate(100L, 5L, 0, 0))
                room.statsDao().accumulateDailyAggregate(100L, 5L, pages = 7, completed = 1)
                val rows = room.statsDao().getDailyAggregatesForProfile(5L)
                assertEquals(7L, rows.single().pagesRead)
                assertEquals(1, rows.single().completed)
            }
        } finally {
            room.close()
            ctx.getDatabasePath(name).delete()
        }
    }
}
