package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Schema-export driven migration tests (audit 2026-10-04 C10 + C31).
 *
 * Each historical database is created from its committed
 * `app/schemas/<version>.json` — exactly what that release's Room built,
 * not hand-written DDL — and then opened through [AppDatabase.build], so the
 * whole migration chain runs and Room validates the result against the
 * current entities. The current export is pinned to the compiled database's
 * identity hash, so an entity change without a version bump fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class RoomSchemaChainTest {

    private lateinit var context: Context
    private lateinit var schemaDir: File
    private lateinit var versions: List<Int>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Unit tests run with the module directory as working directory.
        schemaDir = listOf("schemas", "app/schemas")
            .map { File(it, AppDatabase::class.java.name) }
            .first { it.isDirectory }
        versions = schemaDir.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .sorted()
    }

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject.getValue("database").jsonObject

    /** The DDL Room ran for [version]: tables, indices, views and the master-table setup. */
    private fun statements(version: Int): List<String> {
        val db = schema(version)
        val out = mutableListOf<String>()
        db["entities"]?.jsonArray?.forEach { e ->
            val entity = e.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            out += entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            entity["indices"]?.jsonArray?.forEach { i ->
                out += i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            }
        }
        db["views"]?.jsonArray?.forEach { v ->
            val view = v.jsonObject
            out += view.getValue("createSql").jsonPrimitive.content
                .replace("\${VIEW_NAME}", view.getValue("viewName").jsonPrimitive.content)
        }
        db["setupQueries"]?.jsonArray?.forEach { out += it.jsonPrimitive.content }
        return out
    }

    /** Creates database [name] as release [version] left it, then lets [seed] add rows. */
    private fun createAt(version: Int, name: String, seed: (SupportSQLiteDatabase) -> Unit = {}) {
        context.deleteDatabase(name)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    statements(version).forEach(db::execSQL)
                    seed(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    /** Opens [name] through the production builder (full chain + Room validation) and runs [block]. */
    private fun <T> upgrade(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val db = AppDatabase.build(context, name)
        try {
            return block(db.openHelper.writableDatabase)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun latestExport_matchesCompiledDatabase() {
        val latest = versions.last()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals("newest export must be the compiled version", sql.version, latest)
            val compiledHash = sql.query("SELECT identity_hash FROM room_master_table").use {
                it.moveToFirst()
                it.getString(0)
            }
            assertEquals(
                "app/schemas/$latest.json is stale: bump the database version and commit the new export",
                schema(latest).getValue("identityHash").jsonPrimitive.content,
                compiledHash
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun everyExportedVersion_upgradesToLatest() {
        val latest = versions.last()
        assertTrue("expected exports from v9 on, got $versions", versions.first() <= 9)
        val failures = mutableListOf<String>()
        for (version in versions.dropLast(1)) {
            val name = "chain-$version.db"
            try {
                createAt(version, name)
                val reached = upgrade(name) { it.version }
                if (reached != latest) failures += "v$version reached v$reached"
            } catch (e: Exception) {
                failures += "v$version: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun v20LegacyRows_landInArchiveLocalState() {
        // v20 still has the per-subsystem tables (DOWNLOADS / HISTORY /
        // LOCAL_FAVORITES); 22->23 lifts them into ARCHIVE_LOCAL_STATE and
        // 23->24 drops them, so a row lost on the way is gone for good.
        createAt(20, "chain-v20-rows.db") { db ->
            db.execSQL(
                "INSERT INTO DOWNLOADS (STATE, LEGACY, TIME, GID, ARCID, TITLE, CATEGORY, RATING, SERVER_PROFILE_ID) " +
                    "VALUES (3, 0, 1000, 1, 'arc-dl', 'Downloaded', 0, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO HISTORY (MODE, TIME, GID, ARCID, TITLE, CATEGORY, RATING, SERVER_PROFILE_ID) " +
                    "VALUES (0, 2000, 2, 'arc-hist', 'Read', 0, 0, 1)"
            )
        }

        upgrade("chain-v20-rows.db") { db ->
            db.query(
                "SELECT DOWNLOAD_STATE, DOWNLOAD_TIME, HISTORY_TIME FROM ARCHIVE_LOCAL_STATE WHERE ARCID = 'arc-dl'"
            ).use {
                assertTrue("download row lost", it.moveToFirst())
                assertEquals(3, it.getInt(0))
                assertEquals(1000L, it.getLong(1))
            }
            db.query("SELECT HISTORY_TIME, DOWNLOAD_STATE FROM ARCHIVE_LOCAL_STATE WHERE ARCID = 'arc-hist'").use {
                assertTrue("history row lost", it.moveToFirst())
                assertNotNull(it.getLong(0).takeUnless { _ -> it.isNull(0) })
                assertTrue(it.isNull(1))
            }
            val legacyTables = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('DOWNLOADS', 'HISTORY', 'LOCAL_FAVORITES')"
            ).use { it.count }
            assertEquals(0, legacyTables)
        }
    }

    @Test
    fun v25Row_keepsItsDataThroughTheKeyRebuild() {
        // 25->26 adds DOWNLOAD_ROOT_URI, 26->27 rebuilds the table on the
        // composite (ARCID, SERVER_PROFILE_ID) key.
        createAt(25, "chain-v25-rows.db") { db ->
            db.execSQL(
                "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, " +
                    "DOWNLOAD_LEGACY, DOWNLOAD_TIME, HISTORY_TIME, HISTORY_MODE, HISTORY_SCROLL_FRACTION) " +
                    "VALUES ('arc-25', 4, '{}', 3, 0, 1000, 2000, 0, 0.5)"
            )
        }

        upgrade("chain-v25-rows.db") { db ->
            db.query(
                "SELECT SERVER_PROFILE_ID, DOWNLOAD_STATE, HISTORY_TIME, HISTORY_SCROLL_FRACTION, DOWNLOAD_ROOT_URI " +
                    "FROM ARCHIVE_LOCAL_STATE WHERE ARCID = 'arc-25'"
            ).use {
                assertTrue("row lost", it.moveToFirst())
                assertEquals(4L, it.getLong(0))
                assertEquals(3, it.getInt(1))
                assertEquals(2000L, it.getLong(2))
                assertEquals(0.5, it.getDouble(3), 0.0)
                assertNull(it.getString(4))
            }
        }
    }
}
