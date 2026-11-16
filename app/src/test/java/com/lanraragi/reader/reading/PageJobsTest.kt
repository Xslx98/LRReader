package com.lanraragi.reader.reading

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Audit 2026-10-06 N6: in-flight bookkeeping shared by LRRGalleryProvider and
 * TankGalleryProvider (Tank lacked the rebind double-check and per-request
 * tokens), plus the per-page mutexes and calls (plan 2026-11 PR 4.3).
 */
class PageJobsTest {

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
        val tracker = PageJobs(map)
        val first = tracker.begin(5)
        assertNotNull(first)

        var redispatchFromFinish = true
        map.onCollision = {
            // The cancelled job finishes before the rebind is marked, so its
            // finally sees no rebind and does not re-request.
            redispatchFromFinish = tracker.finish(5, first!!, outcome = PageJobs.Outcome.Cancelled, stopped = false)
        }
        val second = tracker.begin(5)

        assertFalse(redispatchFromFinish)
        assertNotNull("the rebound request must own a new job, or the page spins forever", second)
        assertTrue(map[5] === second)
    }

    @Test
    fun `old job's finish does not clear a force-requested newer job`() {
        val tracker = PageJobs()
        val old = tracker.begin(2)!!
        tracker.forget(2)
        val newer = tracker.begin(2)
        assertNotNull(newer)

        tracker.finish(2, old, outcome = PageJobs.Outcome.Cancelled, stopped = false)

        assertNull("the newer job is still in flight; no duplicate fetch", tracker.begin(2))
    }

    @Test
    fun `rebind during a cancelled job is re-dispatched by its finish`() {
        val tracker = PageJobs()
        val token = tracker.begin(1)!!
        assertNull(tracker.begin(1))
        assertTrue(tracker.finish(1, token, outcome = PageJobs.Outcome.Cancelled, stopped = false))
        assertNotNull(tracker.begin(1))
    }

    @Test
    fun `rebind during a job that published its page needs no re-dispatch`() {
        val tracker = PageJobs()
        val token = tracker.begin(1)!!
        assertNull(tracker.begin(1))
        assertFalse(tracker.finish(1, token, outcome = PageJobs.Outcome.Succeeded, stopped = false))
        // The rebind was consumed: a later cancelled run does not re-dispatch.
        val next = tracker.begin(1)!!
        assertFalse(tracker.finish(1, next, outcome = PageJobs.Outcome.Cancelled, stopped = false))
    }

    @Test
    fun `no re-dispatch after stop`() {
        val tracker = PageJobs()
        val token = tracker.begin(4)!!
        assertNull(tracker.begin(4))
        assertFalse(tracker.finish(4, token, outcome = PageJobs.Outcome.Cancelled, stopped = true))
    }

    @Test
    fun `abandon frees the page and clear drops everything`() {
        val tracker = PageJobs()
        val token = tracker.begin(0)!!
        tracker.abandon(0, token)
        val again = tracker.begin(0)
        assertNotNull(again)
        tracker.begin(7)
        tracker.clear()
        assertNotNull(tracker.begin(0))
        assertNotNull(tracker.begin(7))
    }

    /** A published failure reached the rebound view too (notifies are keyed by index): no second fetch, no flicker. */
    @Test
    fun `rebind during a failed job needs no re-dispatch`() {
        val jobs = PageJobs()
        val token = jobs.begin(6)!!
        assertNull(jobs.begin(6))
        assertFalse(jobs.finish(6, token, outcome = PageJobs.Outcome.Failed, stopped = false))
    }

    @Test
    fun `cancelCall severs only that page's download, clear severs every one`() {
        val client = OkHttpClient()
        val jobs = PageJobs()
        val one = client.newCall(Request.Builder().url("http://127.0.0.1:9/1").build())
        val two = client.newCall(Request.Builder().url("http://127.0.0.1:9/2").build())
        jobs.track(1, one)
        jobs.track(2, two)

        jobs.cancelCall(1)
        assertTrue(one.isCanceled())
        assertFalse(two.isCanceled())

        jobs.clear()
        assertTrue(two.isCanceled())
    }

    @Test
    fun `an untracked download is not severed`() {
        val jobs = PageJobs()
        val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:9/1").build())
        jobs.track(1, call)
        jobs.untrack(1)

        jobs.clear()
        assertFalse(call.isCanceled())
    }

    @Test
    fun `one mutex per page`() {
        val jobs = PageJobs()
        assertTrue(jobs.mutex(3) === jobs.mutex(3))
        assertFalse(jobs.mutex(3) === jobs.mutex(4))
    }
}
