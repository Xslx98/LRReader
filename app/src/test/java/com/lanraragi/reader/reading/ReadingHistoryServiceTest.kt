package com.lanraragi.reader.reading

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.domain.Archive
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadingHistoryService]: a history edit the user started finishes even
 * when the history screen that awaited it is gone, and a batch removal
 * reports its tally. (The list-side behaviour is covered in
 * HistoryViewModelTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ReadingHistoryServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: HistoryRepository
    private lateinit var scope: CoroutineScope

    /** Room work waiting while [holdRoom] is set, so a test can cancel the caller mid-edit. */
    private val heldRoomWork = LinkedBlockingQueue<Runnable>()

    @Volatile
    private var holdRoom = false

    private val gate = Executor { work -> if (holdRoom) heldRoomWork.add(work) else work.run() }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Settings.initialize(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(gate)
            .setTransactionExecutor(gate)
            .build()
        repository = HistoryRepository(db.archiveLocalStateDao(), db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        holdRoom = false
        scope.cancel()
        db.close()
    }

    @Test
    fun remove_reportsTheTally() = runBlocking {
        put("a1", "One")
        put("a2", "Two")

        val tally = ReadingHistoryService(scope, repository).remove(listOf(archive("a1", "One")))

        assertEquals(1 to 0, tally)
        assertEquals(listOf("a2"), repository.getHistoryLazyList().map { it.arcid })
    }

    @Test
    fun remove_finishesWhenTheCallerIsCancelledMidBatch() = runBlocking {
        put("a1", "One")
        put("a2", "Two")
        val service = ReadingHistoryService(scope, repository)

        holdRoom = true
        val caller = CoroutineScope(Dispatchers.IO).launch {
            service.remove(listOf(archive("a1", "One"), archive("a2", "Two")))
        }
        val firstWrite = checkNotNull(heldRoomWork.poll(5, TimeUnit.SECONDS)) { "the edit never reached Room" }
        val edit = scope.coroutineContext.job.children.single() as Deferred<*>
        caller.cancelAndJoin()
        holdRoom = false
        firstWrite.run()
        withTimeout(10_000) {
            while (!edit.isCompleted) heldRoomWork.poll(10, TimeUnit.MILLISECONDS)?.run()
        }

        assertFalse(edit.isCancelled)
        assertEquals(emptyList<String>(), repository.getHistoryLazyList().map { it.arcid })
    }

    private fun put(arcid: String, title: String) = runBlocking { repository.putHistoryInfo(archive(arcid, title)) }

    private fun archive(arcid: String, title: String) = Archive(
        arcid = arcid,
        title = title,
        tags = emptyMap(),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = 0L,
    )
}
