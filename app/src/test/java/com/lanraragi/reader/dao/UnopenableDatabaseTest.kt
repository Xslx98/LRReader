package com.lanraragi.reader.dao

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Audit 2026-10-06d STAB-02 AVD smoke: a database the app cannot open (here
 * one written by a newer version, user_version above ours) made the first
 * Room flow collected on Main throw into lifecycleScope and kill the process
 * before the boot-failure dialog showed. Repository observations must end
 * quietly instead; the boot loader alone reports the failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class UnopenableDatabaseTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = context.getDatabasePath(NAME).parentFile!!
        dir.mkdirs()
        clean()
        writeNewerVersionDatabase(File(dir, NAME))
        db = AppDatabase.build(context, NAME)
    }

    @After
    fun tearDown() {
        db.close()
        clean()
    }

    private fun clean() {
        dir.listFiles().orEmpty().filter { it.name.startsWith(NAME) }.forEach { it.delete() }
    }

    @Test(timeout = 20_000)
    fun profileObservation_endsWithoutThrowing() = runTest {
        assertEndsEmpty(ProfileRepository(db.miscDao()).observeAll())
    }

    @Test(timeout = 20_000)
    fun downloadObservations_endWithoutThrowing() = runTest {
        val repo = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db)
        assertEndsEmpty(repo.observeDownloads())
        assertEndsEmpty(repo.observeTankGroups())
    }

    private suspend fun <T> assertEndsEmpty(flow: Flow<T>) {
        val emitted = flow.toList()
        assertEquals("no rows from an unopenable database", emptyList<T>(), emitted)
        // The file is the user's data: nothing may have moved or replaced it.
        assertTrue(File(dir, NAME).exists())
        assertEquals(VERSION_FROM_THE_FUTURE, versionOnDisk())
    }

    private fun versionOnDisk(): Int =
        SQLiteDatabase.openDatabase(File(dir, NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    private fun writeNewerVersionDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE t(x)")
            it.version = VERSION_FROM_THE_FUTURE
        }
    }

    private companion object {
        const val NAME = "unopenable-test.db"
        const val VERSION_FROM_THE_FUTURE = 999
    }
}
