package com.lanraragi.reader.tankoubon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.tankoubon.TankTagOutbox.Entry
import com.lanraragi.reader.tankoubon.TankTagSyncer.Outcome
import kotlinx.coroutines.CancellationException
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
 * Audit 2026-10-04 REL-24: a member tag edit whose tank re-materialization
 * failed is kept (with the member's ORIGINAL old tags) and retried later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TankTagOutboxTest {

    private val base = "http://192.168.1.10:3000"
    private val synced = mutableListOf<Pair<String, Entry>>()
    private var tanks: List<String> = listOf("TANK_1", "TANK_2")
    private var listFailure: Exception? = null
    private val failingTanks = mutableSetOf<String>()

    private val lister = TankTagOutbox.TankLister { _, _ ->
        listFailure?.let { throw it }
        tanks
    }
    private val tankSync = TankTagOutbox.TankSync { _, tankId, entry ->
        synced += tankId to entry
        if (tankId in failingTanks) Outcome.FAILED else Outcome.WRITTEN
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("tank_tag_outbox_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        TankTagOutbox.installForTesting(prefs)
    }

    @After
    fun tearDown() {
        TankTagOutbox.installForTesting(null)
    }

    private suspend fun apply(old: String, new: String) =
        TankTagOutbox.apply(base, "arc", old, new, lister, tankSync)

    @Test
    fun `every tank synced leaves nothing pending`() = runBlocking {
        assertTrue(apply("a:1", "a:2"))

        assertEquals(listOf("TANK_1", "TANK_2"), synced.map { it.first })
        assertTrue(TankTagOutbox.entries().isEmpty())
    }

    @Test
    fun `one failed tank keeps the edit and the other tanks still sync`() = runBlocking {
        failingTanks += "TANK_1"

        assertFalse(apply("a:1", "a:2"))

        assertEquals(listOf("TANK_1", "TANK_2"), synced.map { it.first })
        assertEquals(listOf(Entry(base, "arc", "a:1", "a:2")), TankTagOutbox.entries())
    }

    @Test
    fun `an unreachable tank list keeps the edit`() = runBlocking {
        listFailure = IOException("offline")

        assertFalse(apply("a:1", "a:2"))

        assertEquals(listOf(Entry(base, "arc", "a:1", "a:2")), TankTagOutbox.entries())
    }

    @Test
    fun `a server without tankoubon routes has nothing to update`() = runBlocking {
        listFailure = LRRHttpException(404)

        assertTrue(apply("a:1", "a:2"))

        assertTrue(TankTagOutbox.entries().isEmpty())
    }

    @Test
    fun `a second edit keeps the first old tags and the latest new tags`() = runBlocking {
        listFailure = IOException("offline")
        apply("a:1", "a:2")
        listFailure = null

        assertTrue(apply("a:2", "a:3"))

        assertEquals("the retry must subtract the ORIGINAL contribution", "a:1", synced.first().second.oldTags)
        assertEquals("a:3", synced.first().second.newTags)
        assertTrue(TankTagOutbox.entries().isEmpty())
    }

    @Test
    fun `a cancelled edit is still recorded for a retry`() = runBlocking {
        val cancelling = TankTagOutbox.TankSync { _, _, _ -> throw CancellationException("screen closed") }

        val thrown = runCatching { TankTagOutbox.apply(base, "arc", "a:1", "a:2", lister, cancelling) }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertEquals(listOf(Entry(base, "arc", "a:1", "a:2")), TankTagOutbox.entries())
    }

    @Test
    fun `flush retries pending edits and clears them once every tank synced`() = runBlocking {
        failingTanks += "TANK_2"
        apply("a:1", "a:2")
        synced.clear()

        TankTagOutbox.flush(lister, tankSync)
        assertEquals("still failing: kept", 1, TankTagOutbox.entries().size)

        failingTanks.clear()
        TankTagOutbox.flush(lister, tankSync)

        assertEquals(Entry(base, "arc", "a:1", "a:2"), synced.last().second)
        assertTrue(TankTagOutbox.entries().isEmpty())
    }

    @Test
    fun `dropServer forgets only that server`() = runBlocking {
        listFailure = IOException("offline")
        apply("a:1", "a:2")
        TankTagOutbox.apply("http://other:3000", "arc", "b:1", "b:2", lister, tankSync)

        TankTagOutbox.dropServer(base)

        assertEquals(listOf("http://other:3000"), TankTagOutbox.entries().map { it.baseUrl })
    }

    @Test
    fun `tag strings with separators survive storage`() = runBlocking {
        listFailure = IOException("offline")
        val old = "artist:a b, title:x;y@z, \"quoted\""

        apply(old, "")

        assertEquals(old, TankTagOutbox.entries().single().oldTags)
    }
}
