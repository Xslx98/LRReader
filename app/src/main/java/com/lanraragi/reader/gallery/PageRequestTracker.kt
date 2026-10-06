package com.lanraragi.reader.gallery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * In-flight page request bookkeeping shared by the reader providers
 * (LRRGalleryProvider, TankGalleryProvider).
 *
 * - One job per page: a second request for an in-flight page is recorded as a
 *   rebind instead of starting a duplicate fetch.
 * - Each job owns a per-request token, so a finishing (old) job never clears
 *   the entry of a newer job started by a force request.
 * - A rebind that lands while a job is in flight is consumed by that job's
 *   [finish]; when the job ended quietly (cancelled) the caller re-requests,
 *   otherwise the page would spin forever. If the job finished between the
 *   collision and the rebind mark (its [finish] already ran), [begin] takes
 *   the page over itself (audit 2026-10-06 N6).
 */
internal class PageRequestTracker(
    private val inflight: ConcurrentMap<Int, Any> = ConcurrentHashMap(),
) {
    private val rebindWanted = ConcurrentHashMap.newKeySet<Int>()

    /**
     * @return this request's token when the caller owns a new job for [index]
     * (pass it to [finish] / [abandon]); null when a job is already in flight
     * and the rebind was recorded for it.
     */
    fun begin(index: Int): Any? {
        val token = Any()
        while (true) {
            if (inflight.putIfAbsent(index, token) == null) return token
            rebindWanted.add(index)
            // The in-flight job may have finished between putIfAbsent and add
            // (its finish already consumed rebindWanted): nobody would
            // re-dispatch, so check again and take over.
            if (inflight.containsKey(index) || !rebindWanted.remove(index)) return null
        }
    }

    /** Drop a job that never started (no scope to launch on). */
    fun abandon(index: Int, token: Any) {
        inflight.remove(index, token)
    }

    /**
     * End the job owning [token].
     *
     * @return true when the caller must re-request [index]: a rebind landed
     * while the job ran and the job ended quietly ([cancelled]) without
     * notifying the page. Success and failure paths already notified it
     * (notify is keyed by index, not requester).
     */
    fun finish(index: Int, token: Any, cancelled: Boolean, stopped: Boolean): Boolean {
        // Only this job's own entry: a force request may already have
        // replaced it with a newer job's token.
        inflight.remove(index, token)
        return rebindWanted.remove(index) && cancelled && !stopped
    }

    /** Forget the in-flight job for [index] so the next [begin] starts a new one (force request). */
    fun forget(index: Int) {
        inflight.remove(index)
    }

    fun clear() {
        inflight.clear()
        rebindWanted.clear()
    }
}
