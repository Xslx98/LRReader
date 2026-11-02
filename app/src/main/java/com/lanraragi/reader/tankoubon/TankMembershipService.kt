package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankMembershipChangedEvent
import com.lanraragi.reader.server.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Moves ONE archive in and out of a server's tankoubons (the detail page's
 * membership sheet). The write is multi-step (membership, then tag
 * materialization and category promotion), so it runs on [scope], a process
 * scope: cutting it off on a rotation or a closed sheet would leave the
 * server half-updated.
 */
class TankMembershipService(
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    private val recentStore: TankRecentStore = TankRecentStore.default,
) {

    /**
     * Starts [apply] on the service's scope. [onResult] runs on that scope's
     * thread with the new membership ids, or with the exception that stopped
     * the write (a locked tank surfaces as `LRRHttpException` 423).
     */
    @Suppress("LongParameterList")
    fun applyAsync(
        conn: ServerConnection,
        arcid: String,
        tanks: List<LRRTankoubonApi.Tankoubon>,
        originalIds: Set<String>,
        checkedIds: Set<String>,
        onResult: (Result<List<String>>) -> Unit,
    ): Job = scope.launch {
        val result = try {
            Result.success(apply(conn, arcid, tanks, originalIds, checkedIds))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
        onResult(result)
    }

    /**
     * Writes the diff between [originalIds] and [checkedIds] over [tanks]
     * (the tanks the sheet listed), then returns the ids of every listed
     * tank [arcid] is now in, in [tanks] order. Throws on the first failed
     * membership call; the follow-ups after it are best-effort.
     */
    suspend fun apply(
        conn: ServerConnection,
        arcid: String,
        tanks: List<LRRTankoubonApi.Tankoubon>,
        originalIds: Set<String>,
        checkedIds: Set<String>,
    ): List<String> {
        val client = conn.client
        val serverUrl = conn.baseUrl
        val joined = mutableListOf<TankMembershipChangedEvent.JoinedTank>()
        val left = mutableListOf<String>()
        // Rule 3 needs the tank's PRE-removal tags and member tags
        // (spec 2026-09-22 §5.2): snapshot every tank being left first.
        val beforeRemoval = tanks
            .filter { it.id in originalIds && it.id !in checkedIds }
            .mapNotNull { TankTagSyncer.snapshot(client, serverUrl, it.id) }
        // Sequential on purpose: the server appends members in call
        // order, and a locked tank should fail fast and visibly.
        for (tank in tanks) {
            val checked = tank.id in checkedIds
            if (checked == tank.id in originalIds) continue
            if (checked) {
                LRRTankoubonApi.addToTankoubon(client, serverUrl, tank.id, arcid)
                val wasEmpty = tank.archives.isEmpty()
                if (wasEmpty) seedTankCoverBestEffort(conn, tank.id)
                joined += TankMembershipChangedEvent.JoinedTank(tank.id, tank.name, wasEmpty)
            } else {
                LRRTankoubonApi.removeFromTankoubon(client, serverUrl, tank.id, arcid)
                left += tank.id
            }
        }
        // Materialize tank tags + promote static categories after the
        // membership writes (best-effort, spec 2026-09-22 §5 / §6).
        for (tank in joined) {
            TankTagSyncer.afterAdd(client, serverUrl, tank.id)
            TankCategorySyncer.afterAdd(client, serverUrl, tank.id, tank.name, listOf(arcid))
        }
        for (snap in beforeRemoval) {
            TankTagSyncer.afterRemove(client, serverUrl, snap, listOf(arcid))
            TankCategorySyncer.afterRemove(client, serverUrl, snap, listOf(arcid))
        }
        TankListCache.invalidate(serverUrl)
        for (tank in joined.asReversed()) recentStore.touch(conn.profileId, tank.id)
        if (joined.isNotEmpty() || left.isNotEmpty()) {
            AppEventBus.postTankMembershipChangedEvent(TankMembershipChangedEvent(arcid, joined, left))
        }
        return tanks.filter { it.id in checkedIds }.map { it.id }
    }

    /**
     * Best-effort cover seed for a tank that was EMPTY before the add:
     * the server's append endpoint never queues thumbnail generation (only
     * the whole-list JSON PUT does), so without this the tank would serve
     * the placeholder cover forever. Mirrors the web client's behavior of
     * ending up with the first member's cover. Failures are swallowed — the
     * membership write already succeeded and the cover is cosmetic.
     */
    private suspend fun seedTankCoverBestEffort(conn: ServerConnection, tankId: String) {
        try {
            LRRTankoubonApi.updateTankThumbnail(conn.client, conn.baseUrl, tankId, globalPage1 = 1)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ignored: Exception) {
            // Cosmetic — see KDoc.
        }
    }

    companion object {
        /** The app's instance, on the process-wide IO scope. */
        val default: TankMembershipService by lazy { TankMembershipService() }
    }
}
