package com.lanraragi.reader.client

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-tank cache-bust stamps for tankoubon cover images.
 *
 * The image pipeline caches by KEY (and its disk cache survives process
 * restarts) while a tank's cover URL never changes — so a cover
 * regenerated server-side (web-client set-cover, member reorder,
 * first-member removal, or any other client) stays shadowed by the stale
 * cached image forever, the "no thumbnail" placeholder included. Cover
 * binds therefore fold [get] into both the cache key and the URL (`ts`).
 *
 * A tank's stamp moves only when its cover may have changed (audit C38;
 * one process-wide stamp used to re-key every tank cover on any tank
 * fetch):
 * - every tank starts at a per-process stamp, so each cover is revalidated
 *   once per process;
 * - an in-app cover write bumps that tank ([bump]);
 * - opening a tank's detail revalidates that one cover ([revalidate]);
 * - a list fetch bumps only the tanks whose member list changed since the
 *   last fetch seen in this process ([observe]).
 * Failed/offline loads touch nothing and keep serving the cached images.
 */
object TankCoverCacheStamp {

    private val processStamp = System.currentTimeMillis()
    private val stamps = ConcurrentHashMap<String, Long>()
    private val memberPrints = ConcurrentHashMap<String, Int>()

    /** Moves on every bump; lists compare it to decide whether to rebind their tank rows. */
    @Volatile
    var generation: Long = 0L
        private set

    /** Cover stamp of [tankId]. */
    fun get(tankId: String): Long = stamps[tankId] ?: processStamp

    /**
     * Call after an in-app cover write for [tankId]. Strictly increasing
     * even within one millisecond, so a bump is always a key change.
     */
    @Synchronized
    fun bump(tankId: String) {
        stamps[tankId] = maxOf(System.currentTimeMillis(), get(tankId) + 1)
        generation++
    }

    /** A detail fetch of [tankId] succeeded: revalidate its cover and remember its members. */
    fun revalidate(tankId: String, memberIds: List<String>) {
        memberPrints[tankId] = memberIds.hashCode()
        bump(tankId)
    }

    /**
     * A list fetch saw [tankId] with [memberIds]: bump it only when the
     * member list (order included — the cover is the first member's)
     * differs from the last one seen in this process.
     */
    fun observe(tankId: String, memberIds: List<String>) {
        val print = memberIds.hashCode()
        val previous = memberPrints.put(tankId, print)
        if (previous != null && previous != print) bump(tankId)
    }

    @VisibleForTesting
    @Synchronized
    internal fun resetForTest() {
        stamps.clear()
        memberPrints.clear()
    }
}
