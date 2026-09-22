package com.lanraragi.framework.beerbelly

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
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
        // Staggered readers so that at every instant someone holds a lock.
        val readers = (1..8).map { n ->
            Thread {
                Thread.sleep(n * 3L)
                while (running.get()) {
                    val pipe = cache.getInputStreamPipe("k")!!
                    pipe.obtain()
                    Thread.sleep(25)
                    pipe.release()
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(100)
        try {
            val done = CountDownLatch(1)
            Thread { cache.clear(); done.countDown() }.apply { isDaemon = true; start() }
            assertTrue("clear() must not starve behind readers", done.await(5, TimeUnit.SECONDS))
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
        val pipe = cache.getInputStreamPipe("a")!!
        pipe.obtain()
        val clearDone = CountDownLatch(1)
        Thread { cache.clear(); clearDone.countDown() }.apply { isDaemon = true; start() }
        Thread.sleep(100) // the clear is now waiting for this thread's pipe
        // Re-entrant use from the holding thread must not deadlock.
        val other = cache.getInputStreamPipe("b")!!
        other.obtain()
        other.release()
        pipe.release()
        assertTrue(clearDone.await(5, TimeUnit.SECONDS))
    }
}
