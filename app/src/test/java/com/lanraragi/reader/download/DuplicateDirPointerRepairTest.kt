package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DownloadDbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DuplicateDirPointerRepairTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadDbRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Suppress("DEPRECATION")
    private suspend fun row(id: String, state: DownloadState) {
        db.archiveLocalStateDao().upsert(
            ArchiveLocalState(arcid = id, serverProfileId = 1L, archiveJson = "{}", downloadState = state, downloadTime = 1L)
        )
    }

    @Test
    fun keepsTheFinishedPointerAndClearsTheOthers() = runTest {
        row("done", DownloadState.FINISH)
        row("queued", DownloadState.NONE)
        repo.putDownloadDirname("done", "Vol 1")
        repo.putDownloadDirname("queued", "vol 1")
        repo.putDownloadDirname("stray", "Vol 1") // minted by a read path, no row

        val outcome = DuplicateDirPointerRepair(repo).run()

        assertEquals(2, outcome.cleared)
        assertEquals("Vol 1", repo.getDownloadDirname("done"))
        assertNull(repo.getDownloadDirname("queued"))
        assertNull(repo.getDownloadDirname("stray"))
    }

    @Test
    fun withoutAFinishedDownload_keepsOneTrackedPointer() = runTest {
        row("a", DownloadState.NONE)
        row("b", DownloadState.NONE)
        repo.putDownloadDirname("a", "Vol 1")
        repo.putDownloadDirname("b", "Vol 1")

        assertEquals(1, DuplicateDirPointerRepair(repo).run().cleared)
        assertEquals("Vol 1", repo.getDownloadDirname("a"))
        assertNull(repo.getDownloadDirname("b"))
    }

    @Test
    fun twoFinishedDownloadsSharingADirectory_areLeftAndReported() = runTest {
        row("a", DownloadState.FINISH)
        row("b", DownloadState.FINISH)
        repo.putDownloadDirname("a", "Vol 1")
        repo.putDownloadDirname("b", "Vol 1")

        val outcome = DuplicateDirPointerRepair(repo).run()

        assertEquals(0, outcome.cleared)
        assertEquals(1, outcome.unresolved)
    }

    @Test
    fun distinctPointersAreUntouched() = runTest {
        repo.putDownloadDirname("a", "Vol 1")
        repo.putDownloadDirname("b", "Vol 2")
        assertEquals(0, DuplicateDirPointerRepair(repo).run().cleared)
    }
}
