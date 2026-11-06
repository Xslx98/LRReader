package com.lanraragi.framework.lib.glview.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFreeListTest {

    @Test
    fun offerRefusesBeyondCapacity_soTheCallerReleasesTheItem() {
        val pool = BoundedFreeList<String>(2)

        assertTrue(pool.offer("a"))
        assertTrue(pool.offer("b"))
        assertFalse(pool.offer("c"))
        assertEquals(2, pool.size)
    }

    @Test
    fun pollReturnsTheMostRecentlyFreedItem() {
        val pool = BoundedFreeList<String>(4)
        pool.offer("a")
        pool.offer("b")

        assertEquals("b", pool.poll())
        assertEquals("a", pool.poll())
        assertNull(pool.poll())
    }

    @Test
    fun drainEmptiesThePool() {
        val pool = BoundedFreeList<String>(4)
        pool.offer("a")
        pool.offer("b")

        assertEquals(setOf("a", "b"), pool.drain().toSet())
        assertEquals(0, pool.size)
        assertTrue(pool.offer("c"))
    }
}
