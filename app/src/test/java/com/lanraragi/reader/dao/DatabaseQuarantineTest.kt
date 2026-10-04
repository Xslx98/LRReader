package com.lanraragi.reader.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Audit 2026-10-04 C04: recovery must move a broken database aside, never
 * delete it. Covers the boot-failure "Reset database" path (direct
 * [DatabaseQuarantine.quarantine]) and SQLite corruption detected while Room
 * opens the file ([AppDatabase.build] wiring the quarantining callback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DatabaseQuarantineTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = context.getDatabasePath(NAME).parentFile!!
        dir.mkdirs()
        clean()
    }

    @After
    fun tearDown() = clean()

    private fun clean() {
        dir.listFiles().orEmpty().filter { it.name.startsWith(NAME) }.forEach { it.delete() }
    }

    private fun file(name: String, content: String) = File(dir, name).apply { writeText(content) }

    @Test
    fun quarantine_renamesMainFileAndSiblingsUnderOneStamp() {
        file(NAME, "main")
        file("$NAME-wal", "wal")
        file("$NAME-shm", "shm")

        val moved = DatabaseQuarantine.quarantine(File(dir, NAME), nowMillis = 1000L)

        assertEquals(File(dir, "$NAME.broken-1000"), moved)
        assertFalse(File(dir, NAME).exists())
        assertFalse(File(dir, "$NAME-wal").exists())
        assertFalse(File(dir, "$NAME-shm").exists())
        assertEquals("main", File(dir, "$NAME.broken-1000").readText())
        // The -wal keeps its pairing with the quarantined main file.
        assertEquals("wal", File(dir, "$NAME.broken-1000-wal").readText())
        assertEquals("shm", File(dir, "$NAME.broken-1000-shm").readText())
    }

    @Test
    fun quarantine_keepsOnlyNewestSets() {
        for (stamp in listOf(100L, 200L, 300L)) {
            file("$NAME.broken-$stamp", "old$stamp")
            file("$NAME.broken-$stamp-wal", "oldwal$stamp")
        }
        file(NAME, "current")

        DatabaseQuarantine.quarantine(File(dir, NAME), nowMillis = 400L, keep = 3)

        assertFalse(File(dir, "$NAME.broken-100").exists())
        assertFalse(File(dir, "$NAME.broken-100-wal").exists())
        assertTrue(File(dir, "$NAME.broken-200").exists())
        assertTrue(File(dir, "$NAME.broken-300-wal").exists())
        assertEquals("current", File(dir, "$NAME.broken-400").readText())
    }

    @Test
    fun quarantine_nothingToMove_returnsNull() {
        assertEquals(null, DatabaseQuarantine.quarantine(File(dir, NAME), nowMillis = 1L))
    }

    @Test
    fun corruptFileOpenedByRoom_isQuarantinedNotDeleted() {
        val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
        File(dir, NAME).writeBytes(garbage)

        val db = AppDatabase.build(context, NAME)
        try {
            // Forces the open; the corrupt file triggers onCorruption, then a fresh schema.
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }

        val mainSet = Regex(Regex.escape(NAME + DatabaseQuarantine.MARKER) + "\\d+")
        val quarantined = dir.listFiles().orEmpty().singleOrNull { mainSet.matches(it.name) }
        assertNotNull("corrupt file must survive as $NAME.broken-*", quarantined)
        assertArrayEquals(garbage, quarantined!!.readBytes())
    }

    private companion object {
        const val NAME = "quarantine-test.db"
    }
}
