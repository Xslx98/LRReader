package com.lanraragi.reader.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Audit 2026-10-06 N6: in-flight bookkeeping shared by LRRGalleryProvider and
 * TankGalleryProvider (Tank lacked the rebind double-check and per-request
 * tokens).
 */
class PageRequestTrackerTest {

    /**
     * Map that runs [onCollision] once, right after a putIfAbsent finds an
     * existing entry: the exact window in which the in-flight job's finally
     * can run before the GL thread marks the rebind.
     */
    private class CollisionHookMap : ConcurrentHashMap<Int, Any>() {
        var onCollision: (() -> Unit)? = null

        override fun putIfAbsent(key: Int, value: Any): Any? {
            val previous = super.putIfAbsent(key, value)
            if (previous != null) {
                val hook = onCollision
                onCollision = null
                hook?.invoke()
            }
            return previous
        }
    }

    @Test
    fun `rebind racing a finishing cancelled job takes the page over`() {
        val map = CollisionHookMap()
        val tracker = PageRequestTracker(map)
        val first = tracker.begin(5)
        assertNotNull(first)

        var redispatchFromFinish = true
        map.onCollision = {
            // The cancelled job finishes before the rebind is marked, so its
            // finally sees no rebind and does not re-request.
            redispatchFromFinish = tracker.finish(5, first!!, cancelled = true, stopped = false)
        }
        val second = tracker.begin(5)

        assertFalse(redispatchFromFinish)
        assertNotNull("the rebound request must own a new job, or the page spins forever", second)
        assertTrue(map[5] === second)
    }

    @Test
    fun `old job's finish does not clear a force-requested newer job`() {
        val tracker = PageRequestTracker()
        val old = tracker.begin(2)!!
        tracker.forget(2)
        val newer = tracker.begin(2)
        assertNotNull(newer)

        tracker.finish(2, old, cancelled = true, stopped = false)

        assertNull("the newer job is still in flight; no duplicate fetch", tracker.begin(2))
    }

    @Test
    fun `rebind during a cancelled job is re-dispatched by its finish`() {
        val tracker = PageRequestTracker()
        val token = tracker.begin(1)!!
        assertNull(tracker.begin(1))
        assertTrue(tracker.finish(1, token, cancelled = true, stopped = false))
        assertNotNull(tracker.begin(1))
    }

    @Test
    fun `rebind during a finished job needs no re-dispatch`() {
        val tracker = PageRequestTracker()
        val token = tracker.begin(1)!!
        assertNull(tracker.begin(1))
        assertFalse(tracker.finish(1, token, cancelled = false, stopped = false))
        // The rebind was consumed: a later cancelled run does not re-dispatch.
        val next = tracker.begin(1)!!
        assertFalse(tracker.finish(1, next, cancelled = true, stopped = false))
    }

    @Test
    fun `no re-dispatch after stop`() {
        val tracker = PageRequestTracker()
        val token = tracker.begin(4)!!
        assertNull(tracker.begin(4))
        assertFalse(tracker.finish(4, token, cancelled = true, stopped = true))
    }

    @Test
    fun `abandon frees the page and clear drops everything`() {
        val tracker = PageRequestTracker()
        val token = tracker.begin(0)!!
        tracker.abandon(0, token)
        val again = tracker.begin(0)
        assertNotNull(again)
        tracker.begin(7)
        tracker.clear()
        assertNotNull(tracker.begin(0))
        assertNotNull(tracker.begin(7))
    }
}
