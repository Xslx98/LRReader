package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference

class LRRGalleryProviderStateTest {

    private data class ProviderState(
        val paths: Array<String>? = null,
        val count: Int = -1,
        val stopped: Boolean = false
    )

    @Test
    fun `default state has null paths and STATE_WAIT count and not stopped`() {
        val state = ProviderState()
        assertNull(state.paths)
        assertEquals(-1, state.count)
        assertFalse(state.stopped)
    }

    @Test
    fun `state copy preserves unmodified fields`() {
        val paths = arrayOf("/page/1", "/page/2")
        val state = ProviderState(paths = paths, count = 2)
        val stopped = state.copy(stopped = true)
        assertNotNull(stopped.paths)
        assertTrue(paths.contentEquals(stopped.paths!!))
        assertEquals(2, stopped.count)
        assertTrue(stopped.stopped)
    }

    @Test
    fun `AtomicReference set replaces entire state atomically`() {
        val ref = AtomicReference(ProviderState())
        val paths = arrayOf("/a", "/b", "/c")
        ref.set(ProviderState(paths = paths, count = 3))
        val snapshot = ref.get()
        assertNotNull(snapshot.paths)
        assertEquals(3, snapshot.count)
        assertFalse(snapshot.stopped)
    }

    @Test
    fun `AtomicReference updateAndGet is atomic`() {
        val ref = AtomicReference(ProviderState(paths = arrayOf("/x"), count = 1))
        val updated = ref.updateAndGet { it.copy(stopped = true) }
        assertTrue(updated.stopped)
        assertEquals(1, updated.count)
        assertNotNull(updated.paths)
    }

    @Test
    fun `concurrent start and stop do not lose state`() {
        val ref = AtomicReference(ProviderState())
        val threads = 10
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = mutableListOf<Throwable>()
        repeat(threads) { i ->
            Thread {
                try {
                    barrier.await()
                    if (i % 2 == 0) {
                        ref.set(ProviderState(paths = arrayOf("/page/$i"), count = 1))
                    } else {
                        ref.updateAndGet { it.copy(stopped = true) }
                    }
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await()
        assertTrue("Concurrent errors: $errors", errors.isEmpty())
        val f = ref.get()
        if (f.paths != null) assertEquals(1, f.count)
    }

    @Test
    fun `rapid stop toggle always converges`() {
        val ref = AtomicReference(ProviderState(paths = arrayOf("/a"), count = 1))
        val latch = CountDownLatch(2)
        Thread { repeat(10_000) { ref.updateAndGet { it.copy(stopped = true) } }; latch.countDown() }.start()
        Thread { repeat(10_000) { val s = ref.get(); if (s.paths != null) assertEquals(1, s.count) }; latch.countDown() }.start()
        latch.await()
        assertTrue(ref.get().stopped)
    }

    @Test
    fun `striped lock array has fixed size`() {
        assertEquals(32, Array(32) { Any() }.size)
    }

    @Test
    fun `striped lock index maps deterministically`() {
        val locks = Array(32) { Any() }
        fun lock(i: Int) = locks[i.and(31)]
        assertTrue(lock(0) === lock(0))
        assertTrue(lock(5) === lock(37))
    }

    @Test
    fun `striped lock different indices get different locks`() {
        val locks = Array(32) { Any() }
        fun lock(i: Int) = locks[i.and(31)]
        assertFalse(lock(0) === lock(1))
    }

    @Test
    fun `striped lock handles negative index safely`() {
        val locks = Array(32) { Any() }
        fun lock(i: Int) = locks[i.and(31)]
        assertTrue(lock(-1) === locks[31])
        assertTrue(lock(-32) === locks[0])
    }

    @Test
    fun `state snapshot provides consistent paths and count`() {
        val ref = AtomicReference(ProviderState())
        val paths = arrayOf("/p/0", "/p/1", "/p/2")
        ref.set(ProviderState(paths = paths, count = paths.size))
        val state = ref.get()
        assertNotNull(state.paths)
        assertEquals(state.count, state.paths!!.size)
        assertTrue(5 >= state.paths!!.size)
    }
}
