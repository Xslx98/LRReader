package com.lanraragi.reader.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.LRRHttpException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Audit 2026-10-04 C20: a single-archive progress PUT that fails is kept and
 * pushed later, unless another device has read the archive since.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ArchiveProgressOutboxTest {

    private val base = "http://192.168.1.10:3000"
    private var now = 1_000_000L
    private val puts = mutableListOf<Triple<String, String, Int>>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("outbox_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ArchiveProgressOutbox.installForTesting(prefs)
        ArchiveProgressOutbox.clockSeconds = { now }
    }

    @After
    fun tearDown() {
        ArchiveProgressOutbox.installForTesting(null)
        ArchiveProgressOutbox.clockSeconds = { System.currentTimeMillis() / 1000L }
    }

    private suspend fun flushWith(serverLastRead: Long) = ArchiveProgressOutbox.flush(
        fetch = { _, _ -> ArchiveProgressOutbox.ServerProgress(serverLastRead) },
        put = { b, a, p -> puts += Triple(b, a, p) },
    )

    @Test
    fun `failed put is recorded and a later success clears it`() = runBlocking {
        val failure = runCatching {
            ArchiveProgressOutbox.tracked(base, "arc", 41) { throw IOException("offline") }
        }
        assertTrue("the failure must reach the caller", failure.exceptionOrNull() is IOException)
        assertEquals(listOf(ArchiveProgressOutbox.Entry(base, "arc", 41, now)), ArchiveProgressOutbox.entries())

        ArchiveProgressOutbox.tracked(base, "arc", 41) {}

        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
    }

    /** Audit 2026-10-06 C20: a blip fails page 10, pages 11-15 go through, the network returns. */
    @Test
    fun `a newer success drops an older pending page so a flush cannot rewind the server`() = runBlocking {
        runCatching { ArchiveProgressOutbox.tracked(base, "arc", 10) { throw IOException("blip") } }
        for (page in 11..15) {
            now += 5
            ArchiveProgressOutbox.tracked(base, "arc", page) {}
        }

        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
        flushWith(serverLastRead = now)
        assertTrue("page 10 must not be pushed over page 15", puts.isEmpty())
    }

    @Test
    fun `an older put completing late keeps the newer pending page`() = runBlocking {
        ArchiveProgressOutbox.tracked(base, "arc", 41) {
            // While page 41's PUT is in flight, page 50 is read and its PUT fails.
            now += 10
            ArchiveProgressOutbox.markPending(base, "arc", 50)
        }

        assertEquals(listOf(ArchiveProgressOutbox.Entry(base, "arc", 50, now)), ArchiveProgressOutbox.entries())
    }

    @Test
    fun `a success in the same second as the failure supersedes it`() = runBlocking {
        runCatching { ArchiveProgressOutbox.tracked(base, "arc", 10) { throw IOException("blip") } }

        ArchiveProgressOutbox.tracked(base, "arc", 11) {}

        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
    }

    @Test
    fun `flush pushes the pending page when the server is not newer`() = runBlocking {
        ArchiveProgressOutbox.markPending(base, "arc", 41)

        flushWith(serverLastRead = now - 3_600)

        assertEquals(listOf(Triple(base, "arc", 42)), puts)
        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
    }

    @Test
    fun `flush drops the page when another device read later`() = runBlocking {
        ArchiveProgressOutbox.markPending(base, "arc", 41)

        flushWith(serverLastRead = now + 3_600)

        assertTrue(puts.isEmpty())
        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
    }

    @Test
    fun `network failure keeps the entry, a 4xx drops it`() = runBlocking {
        ArchiveProgressOutbox.markPending(base, "kept", 1)
        ArchiveProgressOutbox.markPending(base, "gone", 2)

        ArchiveProgressOutbox.flush(
            fetch = { _, arcid ->
                if (arcid == "gone") throw LRRHttpException(400) else throw IOException("down")
            },
            put = { _, _, _ -> },
        )

        assertEquals(listOf("kept"), ArchiveProgressOutbox.entries().map { it.arcid })
    }

    @Test
    fun `shouldPush tolerates clock skew within the grace window`() {
        val grace = ReadingProgressReconciler.CLOCK_SKEW_GRACE_SECONDS
        assertTrue(ArchiveProgressOutbox.shouldPush(readAtSeconds = 1000, serverLastReadSeconds = 1000 + grace))
        assertFalse(ArchiveProgressOutbox.shouldPush(readAtSeconds = 1000, serverLastReadSeconds = 1001 + grace))
    }

    @Test
    fun `dropServer removes only that server's pending pages`() {
        ArchiveProgressOutbox.markPending(base, "arc", 4)
        ArchiveProgressOutbox.markPending("http://other", "arc", 9)

        ArchiveProgressOutbox.dropServer(base)

        assertEquals(listOf("http://other"), ArchiveProgressOutbox.entries().map { it.baseUrl })
    }
}
