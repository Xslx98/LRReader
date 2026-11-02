package com.lanraragi.reader.reading

import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.domain.Archive
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadingHistoryRecorder]: a single archive enters history as itself, a tank
 * session as ONE row keyed by the tank id with every member's pages and the
 * tank cover route of its source server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ReadingHistoryRecorderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val rows = CopyOnWriteArrayList<Archive>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun recorder(baseUrlOf: suspend (Long) -> String = { "http://lrr.test:3000" }) =
        ReadingHistoryRecorder(scope, put = { rows += it }, baseUrlOf = baseUrlOf)

    @Test
    fun anArchiveSession_recordsTheArchiveItself() {
        val archive = archive("a".repeat(40))

        recorder().record(archive, tankSeed = null)

        awaitUntil { rows.size == 1 }
        assertEquals(archive, rows.single())
    }

    @Test
    fun aTankSession_recordsOneRowForTheWholeTank_onItsSourceServer() {
        val seed = TankSessionSeed(
            TANK_ID, "Saga", 7L,
            listOf(TankMemberSeed("a", "A", 3), TankMemberSeed("b", "B", -1), TankMemberSeed("c", "C", 5)),
        )
        val asked = CopyOnWriteArrayList<Long>()

        recorder { asked += it; "http://lrr.test:3000" }.record(archive = null, tankSeed = seed)

        awaitUntil { rows.size == 1 }
        val row = rows.single()
        assertEquals(listOf(7L), asked.toList())
        assertEquals(TANK_ID, row.arcid)
        assertEquals("Saga", row.title)
        assertEquals(8, row.pagecount)
        assertEquals(7L, row.serverProfileId)
        assertEquals("http://lrr.test:3000/api/tankoubons/$TANK_ID/thumbnail", row.thumbnailUrl)
    }

    @Test
    fun aFailedLookup_dropsOnlyThatRow() {
        val seed = TankSessionSeed(TANK_ID, "Saga", 7L, emptyList())
        val archive = archive("b".repeat(40))

        recorder { error("profile gone") }.record(archive, seed)

        awaitUntil { rows.size == 1 }
        assertEquals(archive, rows.single())
    }

    private fun archive(arcid: String) = Archive(
        arcid = arcid,
        title = "t",
        tags = emptyMap(),
        pagecount = 4,
        progress = 0,
        extension = "zip",
        filename = "t.zip",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = 1L,
    )

    private companion object {
        const val TANK_ID = "TANK_1700000000"
    }
}
