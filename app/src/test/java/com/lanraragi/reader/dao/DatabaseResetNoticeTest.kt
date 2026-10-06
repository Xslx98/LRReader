package com.lanraragi.reader.dao

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Audit 2026-10-06d REL-02: a corrupt database moved aside while Room opens
 * it must be reported to the user once — the newest quarantine newer than
 * the acknowledged stamp is pending, nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DatabaseResetNoticeTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = context.getDatabasePath(NAME).parentFile!!
        dir.mkdirs()
        prefs = context.getSharedPreferences("reset-notice-test", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }
        clean()
    }

    @After
    fun tearDown() = clean()

    private fun clean() {
        dir.listFiles().orEmpty().filter { it.name.startsWith(NAME) }.forEach { it.delete() }
    }

    private val dbFile get() = File(dir, NAME)

    @Test
    fun noQuarantine_nothingPending() {
        File(dir, NAME).writeText("healthy")
        assertNull(DatabaseResetNotice.pendingStamp(dbFile, prefs))
    }

    @Test
    fun corruptFileOpenedByRoom_isPendingUntilMarkedSeen() {
        File(dir, NAME).writeBytes(ByteArray(8192) { (it * 31 + 7).toByte() })
        val db = AppDatabase.build(context, NAME)
        try {
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }

        val stamp = DatabaseResetNotice.pendingStamp(dbFile, prefs)
        assertNotNull("a quarantine during open must be pending", stamp)

        DatabaseResetNotice.markSeen(prefs, stamp!!)
        assertNull(DatabaseResetNotice.pendingStamp(dbFile, prefs))
    }

    @Test
    fun onlyTheNewestUnseenQuarantineIsPending() {
        File(dir, "$NAME${DatabaseQuarantine.MARKER}100").writeText("old")
        File(dir, "$NAME${DatabaseQuarantine.MARKER}300-wal").writeText("new wal")
        File(dir, "$NAME${DatabaseQuarantine.MARKER}300").writeText("new")
        assertEquals(300L, DatabaseResetNotice.pendingStamp(dbFile, prefs))

        DatabaseResetNotice.markSeen(prefs, 300L)
        assertNull(DatabaseResetNotice.pendingStamp(dbFile, prefs))
        // An older stamp never moves the acknowledged one back.
        DatabaseResetNotice.markSeen(prefs, 100L)
        assertNull(DatabaseResetNotice.pendingStamp(dbFile, prefs))

        File(dir, "$NAME${DatabaseQuarantine.MARKER}400").writeText("newer")
        assertEquals(400L, DatabaseResetNotice.pendingStamp(dbFile, prefs))
    }

    @Test
    fun userChosenReset_stampOfTheMovedFileMarksItSeen() {
        File(dir, NAME).writeText("db")
        val moved = DatabaseQuarantine.quarantine(dbFile, nowMillis = 777L)
        val stamp = DatabaseQuarantine.stampOf(moved!!, NAME)
        assertEquals(777L, stamp)

        DatabaseResetNotice.markSeen(prefs, stamp!!, commit = true)
        assertNull(DatabaseResetNotice.pendingStamp(dbFile, prefs))
    }

    private companion object {
        const val NAME = "reset-notice-test.db"
    }
}
