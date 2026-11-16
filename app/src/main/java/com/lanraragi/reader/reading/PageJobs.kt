package com.lanraragi.reader.reading

import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Everything a page source tracks about its page jobs, in one place
 * (LRRGalleryProvider, TankGalleryProvider, LrrTankMemberSource):
 *
 * - One job per page: a second request for an in-flight page is recorded as a
 *   rebind instead of starting a duplicate fetch.
 * - Each job owns a per-request token, so a finishing (old) job never clears
 *   the entry of a newer job started by a force request.
 * - A rebind that lands while a job is in flight is consumed by that job's
 *   [finish]; when the job ended without publishing anything for the page
 *   ([Outcome.Cancelled]) the caller re-requests it, or the rebound page
 *   spins forever. A published success or failure reached the rebound view
 *   too (notifies are keyed by index), so it is not fetched again. If the
 *   job finished between the collision and the rebind mark (its [finish]
 *   already ran), [begin] takes the page over itself (audit 2026-10-06 N6).
 * - A per-page [mutex] keeps the on-demand and prefetch paths from fetching
 *   the same page twice; it is suspending and cancellable.
 * - The page's HTTP [Call] while it downloads ([track]): coroutine
 *   cancellation cannot interrupt a blocking body read, so a cancel or a stop
 *   severs the socket ([cancelCall], [clear]).
 */
internal class PageJobs(
    private val inflight: ConcurrentMap<Int, Any> = ConcurrentHashMap(),
) {
    /** How a page job ended. */
    enum class Outcome {
        /** The page was published. */
        Succeeded,

        /** The page's failure was published. */
        Failed,

        /**
         * Nothing was published for the page as it is now: cancelled, or
         * published under a page map that has moved since (a tank remap).
         */
        Cancelled,
    }

    private val rebindWanted = ConcurrentHashMap.newKeySet<Int>()
    private val mutexes = ConcurrentHashMap<Int, Mutex>()
    private val calls = ConcurrentHashMap<Int, Call>()

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
     * while the job ran, the job published nothing for the page
     * ([Outcome.Cancelled]) and the source is not [stopped].
     */
    fun finish(index: Int, token: Any, outcome: Outcome, stopped: Boolean): Boolean {
        // Only this job's own entry: a force request may already have
        // replaced it with a newer job's token.
        inflight.remove(index, token)
        return rebindWanted.remove(index) && outcome == Outcome.Cancelled && !stopped
    }

    /** Forget the in-flight job for [index] so the next [begin] starts a new one (force request). */
    fun forget(index: Int) {
        inflight.remove(index)
    }

    /** The page's fetch lock. Bounded by the page count; entries live as long as this object. */
    fun mutex(index: Int): Mutex = mutexes.computeIfAbsent(index) { Mutex() }

    /** [call] downloads page [index] now (one at a time per page, under [mutex]). */
    fun track(index: Int, call: Call) {
        calls[index] = call
    }

    /** Page [index]'s download ended. */
    fun untrack(index: Int) {
        calls.remove(index)
    }

    /** Sever page [index]'s download; the blocked read throws and the job ends quietly. */
    fun cancelCall(index: Int) {
        calls[index]?.cancel()
    }

    /** The source stops: sever every download and forget every job and rebind. */
    fun clear() {
        calls.values.forEach { runCatching { it.cancel() } }
        calls.clear()
        inflight.clear()
        rebindWanted.clear()
    }
}
