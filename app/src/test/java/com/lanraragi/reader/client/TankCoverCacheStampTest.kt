package com.lanraragi.reader.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Audit C38: tank cover stamps move per tank, only when that cover may have changed. */
class TankCoverCacheStampTest {

    private val a = "TANK_1700000001"
    private val b = "TANK_1700000002"

    @Before
    fun setUp() {
        TankCoverCacheStamp.resetForTest()
    }

    @Test
    fun `bump moves only that tank and the generation`() {
        val stampB = TankCoverCacheStamp.get(b)
        val gen = TankCoverCacheStamp.generation
        TankCoverCacheStamp.bump(a)
        assertTrue(TankCoverCacheStamp.get(a) > stampB)
        assertEquals(stampB, TankCoverCacheStamp.get(b))
        assertTrue(TankCoverCacheStamp.generation > gen)
    }

    @Test
    fun `list fetches re-key only tanks whose member list changed`() {
        TankCoverCacheStamp.observe(a, listOf("m1", "m2"))
        TankCoverCacheStamp.observe(b, listOf("m3"))
        val stampA = TankCoverCacheStamp.get(a)
        val stampB = TankCoverCacheStamp.get(b)
        val gen = TankCoverCacheStamp.generation

        // Same lists again: nothing moves.
        TankCoverCacheStamp.observe(a, listOf("m1", "m2"))
        TankCoverCacheStamp.observe(b, listOf("m3"))
        assertEquals(stampA, TankCoverCacheStamp.get(a))
        assertEquals(gen, TankCoverCacheStamp.generation)

        // Reorder (the cover is the first member's) re-keys A only.
        TankCoverCacheStamp.observe(a, listOf("m2", "m1"))
        TankCoverCacheStamp.observe(b, listOf("m3"))
        assertNotEquals(stampA, TankCoverCacheStamp.get(a))
        assertEquals(stampB, TankCoverCacheStamp.get(b))
    }

    @Test
    fun `a detail fetch revalidates that cover and records its members`() {
        val before = TankCoverCacheStamp.get(a)
        TankCoverCacheStamp.revalidate(a, listOf("m1"))
        val afterDetail = TankCoverCacheStamp.get(a)
        assertTrue(afterDetail > before)
        // The following list fetch sees the same members: no second re-key.
        TankCoverCacheStamp.observe(a, listOf("m1"))
        assertEquals(afterDetail, TankCoverCacheStamp.get(a))
    }

    @Test
    fun `stamps are never zero so tank urls always carry ts`() {
        assertTrue(TankCoverCacheStamp.get(a) > 0L)
    }
}
