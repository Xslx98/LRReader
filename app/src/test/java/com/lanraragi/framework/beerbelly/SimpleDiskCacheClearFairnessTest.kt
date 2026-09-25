package com.lanraragi.framework.beerbelly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * clear() needs the cache idle. Under steady overlapping reads it used to
 * wait forever (a new reader always slipped in before the last one left);
 * readers now yield to a waiting clear (audit 2026-09-22 A36).
 */
class SimpleDiskCacheClearFairnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun clearCompletesWhileReadersKeepOverlapping() {
        val cache = SimpleDiskCache(tmp.newFolder(), 4 * 1024 * 1024)
        cache.put("k", ByteArrayInputStream(ByteArray(64)))
        val running = AtomicBoolean(true)
        // Reader failures happen off the test thread; collect them so a dead
        // reader (fewer overlapping readers) cannot make clear() pass trivially.
        val errors = CopyOnWriteArrayList<Throwable>()
        // Staggered readers so that at every instant someone holds a lock.
        val readers = (1..8).map { n ->
            Thread {
                try {
                    Thread.sleep(n * 3L)
                    while (running.get()) {
                        // After clear() the entry is gone; keep the loop alive.
                        val pipe = cache.getInputStreamPipe("k")
                        if (pipe == null) { Thread.sleep(5); continue }
                        pipe.obtain()
                        Thread.sleep(25)
                        pipe.release()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(100) // let the staggered readers overlap before clearing
        try {
            val done = CountDownLatch(1)
            Thread { cache.clear(); done.countDown() }.apply { isDaemon = true; start() }
            assertTrue("clear() must not starve behind readers", done.await(5, TimeUnit.SECONDS))
            assertTrue("readers must still be running when clear() returns",
                readers.all { it.isAlive })
            assertTrue("reader threads failed: $errors", errors.isEmpty())
        } finally {
            running.set(false)
            readers.forEach { it.join(2000) }
        }
    }

    @Test
    fun aThreadHoldingAPipeIsNotBlockedByAWaitingClear() {
        val cache = SimpleDiskCache(tmp.newFolder(), 4 * 1024 * 1024)
        cache.put("a", ByteArrayInputStream(ByteArray(8)))
        cache.put("b", ByteArrayInputStream(ByteArray(8)))
        val clearDone = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        lateinit var clearer: Thread
        // The holder runs off the test thread: if a regression deadlocks it,
        // the test fails on the join timeout instead of hanging the suite.
        val holder = Thread {
            try {
                val pipe = cache.getInputStreamPipe("a")!!
                pipe.obtain()
                holding.countDown()
                // Proceed only once clear() is really parked waiting for this
                // thread's pipe; otherwise the re-entrant read proves nothing.
                val deadline = System.currentTimeMillis() + 5_000
                while (clearer.state != Thread.State.WAITING) {
                    check(System.currentTimeMillis() < deadline) { "clear() never started waiting" }
                    Thread.sleep(5)
                }
                // Re-entrant use from the holding thread must not deadlock.
                val other = cache.getInputStreamPipe("b")!!
                other.obtain()
                other.release()
                pipe.release()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }.apply { isDaemon = true }
        clearer = Thread { cache.clear(); clearDone.countDown() }.apply { isDaemon = true }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        clearer.start()
        holder.join(10_000)
        assertFalse("the pipe holder deadlocked behind the waiting clear()", holder.isAlive)
        assertTrue("holder failed: $errors", errors.isEmpty())
        assertTrue(clearDone.await(5, TimeUnit.SECONDS))
    }
}
