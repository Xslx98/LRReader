package com.lanraragi.reader.tankoubon

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.cover.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.archiveThumbnailUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.server.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Server-side tankoubon list operations (docs/plan-2.4.0-tank-ux.md, PR-B):
 * the full list load with its side effects, the missing-cover probe,
 * auto-sort, the member fetch a download needs, create and delete. Every call
 * names its server with a [ServerConnection]. Plain suspend functions —
 * callers own feedback; failures propagate as exceptions.
 *
 * @param membershipSync hands a fresh list's member lists to
 *   [TankMembershipSync] (spec 2026-09-21 §1/§3) so downloaded group rows and
 *   member history rows follow the server; the UI layer wires the production
 *   runner, tests a recorder
 * @param background process scope this repository owns: the fire-and-forget
 *   membership sync after every load, and [delete]'s write sequence, which
 *   must not be cut in half by a closing screen
 */
class TankRepository(
    private val membershipSync: suspend (
        profileId: Long,
        baseUrl: String,
        tanks: List<TankMembershipSync.TankTruth>,
    ) -> Unit,
    private val background: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
) {

    /** Stand-in cover for a tank whose generated cover is missing server-side. */
    data class CoverFallback(val arcid: String, val thumbnailUrl: String)

    /** A tank's membership resolved for a download fill. */
    data class Members(
        val tankId: String,
        val name: String,
        val members: List<Archive>,
        val memberIdsInOrder: List<String>,
    )

    /**
     * Pages through GET /api/tankoubons until all `total` entries are held.
     * Server pages are 0-BASED (page 0 = parameterless request); a 1-based
     * loop starts on the empty second page and shows a blank list. Fresh
     * server truth re-keys the covers of tanks whose member list changed,
     * refreshes [TankListCache] and starts the membership sync.
     */
    suspend fun loadAll(conn: ServerConnection): List<LRRTankoubonApi.Tankoubon> {
        val serverUrl = conn.baseUrl
        val all = mutableListOf<LRRTankoubonApi.Tankoubon>()
        var page = 0
        while (page < MAX_PAGES) {
            val r = LRRTankoubonApi.getTankoubons(conn.client, serverUrl, page)
            all.addAll(r.result)
            if (r.result.isEmpty() || all.size >= r.total) break
            page++
        }
        for (tank in all) TankCoverCacheStamp.observe(tank.id, tank.archives)
        TankListCache.put(serverUrl, all)
        syncMembership(conn.profileId, serverUrl, all)
        return ArrayList(all)
    }

    private fun syncMembership(profileId: Long, serverUrl: String, tanks: List<LRRTankoubonApi.Tankoubon>) {
        val truth = tanks.map {
            TankMembershipSync.TankTruth(it.id, it.name, it.archives, it.progress, pagecount = 0)
        }
        background.launch {
            try {
                membershipSync(profileId, serverUrl, truth)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                Log.w(TAG, "tank membership sync failed")
            }
        }
    }

    /**
     * Probes each member-having tank for a generated cover; the missing ones
     * ([LRRTankoubonApi.hasTankThumbnail] 202, which also queues server-side
     * generation) map to their FIRST member's cover as a visual stand-in —
     * matching what the tank's own cover would show once generated. A probe
     * failure degrades to "has cover" (plain URL → server placeholder). Empty
     * tanks are skipped: nothing to stand in AND the server-side generation
     * job would just fail.
     */
    suspend fun probeCoverFallbacks(
        conn: ServerConnection,
        tanks: List<LRRTankoubonApi.Tankoubon>,
    ): Map<String, CoverFallback> = coroutineScope {
        val serverUrl = conn.baseUrl
        val gate = Semaphore(PROBE_PARALLELISM)
        tanks.filter { it.archives.isNotEmpty() && coverKey(serverUrl, it.id) !in confirmedCovers }
            .map { tank ->
                async {
                    val missing = try {
                        gate.withPermit { !LRRTankoubonApi.hasTankThumbnail(conn.client, serverUrl, tank.id) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ignored: Exception) {
                        false
                    }
                    if (!missing) {
                        confirmedCovers += coverKey(serverUrl, tank.id)
                        return@async null
                    }
                    val arcid = tank.archives.first()
                    val fallback = try {
                        CoverFallback(arcid, archiveThumbnailUrl(serverUrl, arcid))
                    } catch (ignored: Exception) {
                        null // malformed arcid — keep the plain tank URL
                    }
                    fallback?.let { tank.id to it }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    /**
     * Auto-sort (spec 2026-09-21 §3): fetch the current membership, sort it by
     * the episode-aware title key and PUT the whole order.
     * @return the previous order for undo, or null when already sorted (no PUT)
     */
    suspend fun autoSort(conn: ServerConnection, tankId: String): List<String>? {
        val full = LRRTankoubonApi.getTankoubonFull(conn.client, conn.baseUrl, tankId).result
        val titles = full.fullData.associate { it.arcid to it.title }
        val sorted = TankMemberOrderOps.sortByTitle(full.archives) { titles[it].orEmpty() }
        if (sorted == full.archives) return null
        LRRTankoubonApi.updateTankoubon(conn.client, conn.baseUrl, tankId, archives = sorted)
        return full.archives
    }

    /** Undo of [autoSort]: PUT [previousOrder] back. */
    suspend fun restoreOrder(conn: ServerConnection, tankId: String, previousOrder: List<String>) {
        LRRTankoubonApi.updateTankoubon(conn.client, conn.baseUrl, tankId, archives = previousOrder)
    }

    /** Current membership in tank order, mapped for a download fill. */
    suspend fun members(conn: ServerConnection, tankId: String): Members {
        val full = LRRTankoubonApi.getTankoubonFull(conn.client, conn.baseUrl, tankId).result
        val byId = full.fullData.associateBy { it.arcid }
        val members = full.archives.mapNotNull { id ->
            byId[id]?.toArchive(sourceProfileId = conn.profileId, sourceBaseUrl = conn.baseUrl)
        }
        return Members(tankId, full.name, members, full.archives)
    }

    /** Creates an empty tank named [name]; returns its id. */
    suspend fun create(conn: ServerConnection, name: String): String {
        val id = LRRTankoubonApi.createTankoubon(conn.client, conn.baseUrl, name)
        TankListCache.invalidate(conn.baseUrl)
        return id
    }

    /**
     * Deletes the tank (never its archives). Upstream leaves the deleted id
     * dangling in static categories (spec 2026-09-22 §6): cleared first,
     * while the tank still exists. A downloaded-tank grouping dissolves;
     * member downloads reappear standalone (files untouched). Runs on
     * [background]: a caller that goes away mid-way never leaves the
     * categories cleared but the tank alive.
     */
    suspend fun delete(conn: ServerConnection, tankId: String) {
        background.async {
            val c = conn.client
            val serverUrl = conn.baseUrl
            TankCategorySyncer.onDissolve(c, serverUrl, tankId, TankCategorySyncer.nameOf(c, serverUrl, tankId))
            LRRTankoubonApi.deleteTankoubon(c, serverUrl, tankId)
            TankListCache.invalidate(serverUrl)
            ServiceRegistry.dataModule.downloadManager.dissolveTankGroupAsync(tankId)
        }.await()
    }

    private companion object {
        /**
         * `server|tank` pairs whose generated cover was confirmed: never
         * probed again in this process (a cover, once generated, does not
         * go away). Process-wide so reopening the list does not re-probe
         * every tank (audit C38 / SCAL-09).
         */
        val confirmedCovers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun coverKey(serverUrl: String, tankId: String) = "$serverUrl|$tankId"

        const val MAX_PAGES = 100
        const val PROBE_PARALLELISM = 6
        const val TAG = "TankRepository"
    }
}
