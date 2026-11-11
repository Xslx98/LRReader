package com.lanraragi.reader.tankoubon

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.cover.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.archiveThumbnailUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.Tankoubon
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.TankoubonSupportGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

/**
 * Server-side tankoubon list operations (docs/plan-2.4.0-tank-ux.md, PR-B):
 * the full list load with its side effects, the missing-cover probe,
 * auto-sort, the member fetch a download needs, the reverse lookup, create and delete,
 * plus the single-tank reads and writes of the tank detail and member management pages. Every call
 * names its server with a [ServerConnection]. Plain suspend functions —
 * callers own feedback; failures propagate as exceptions.
 *
 * @param membershipSync hands a fresh list's member lists to
 *   [TankMembershipSync] (spec 2026-09-21 §1/§3) so downloaded group rows and
 *   member history rows follow the server; the UI layer wires the production
 *   runner, tests a recorder
 * @param background process scope this repository owns: the fire-and-forget
 *   membership sync after every load, and [delete]'s write sequence, which
 *   must not be cut in half by a closing screen; null = the app's ioScope,
 *   resolved at first use
 * @param deleteSteps [delete]'s follow-ups around the server DELETE
 */
class TankRepository(
    private val membershipSync: suspend (
        profileId: Long,
        baseUrl: String,
        tanks: List<TankMembershipSync.TankTruth>,
    ) -> Unit,
    background: CoroutineScope? = null,
    private val deleteSteps: DeleteSteps = DeleteSteps(),
) {

    private val background: CoroutineScope by lazy { background ?: ServiceRegistry.coroutineModule.ioScope }

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
    suspend fun loadAll(conn: ServerConnection): List<Tankoubon> {
        val all = fetchPages(conn)
        for (tank in all) TankCoverCacheStamp.observe(tank.id, tank.archives)
        TankListCache.put(conn.baseUrl, all)
        syncMembership(conn.profileId, conn.baseUrl, all)
        return ArrayList(all)
    }

    /**
     * The tank and its members (in tank order) for the tank detail and member management pages. Member ORDER
     * is `archives` (the same list a reorder PUTs); `full_data` is only the
     * metadata lookup, never trusted for order, and members it holds that
     * `archives` lacks follow at the end. Members are mapped with [conn] as
     * their EXPLICIT source (multi-profile red line: the tank may belong to a
     * non-active profile). Success marks the server supported in
     * [TankoubonSupportGate].
     *
     * @param autoFillTags the detail page's first-open auto-fill (spec
     *   2026-09-22 §4.5): a tank with no tags of its own gets the members'
     *   union written once, silently, from this same fetch
     *   ([TankTagSyncer.autoFillIfNeeded]); the returned tank then carries
     *   the written string
     */
    suspend fun detail(
        conn: ServerConnection,
        tankId: String,
        autoFillTags: Boolean = false,
    ): Pair<Tankoubon, List<Archive>> {
        val full = LRRTankoubonApi.getTankoubonFull(conn.client, conn.baseUrl, tankId).result
        TankoubonSupportGate.markSupported(conn.baseUrl)
        val tags = if (autoFillTags) TankTagSyncer.autoFillIfNeeded(conn.client, conn.baseUrl, full) ?: full.tags else full.tags
        val byId = full.fullData.associateBy { it.arcid }
        val ordered = full.archives.mapNotNull { byId[it] } +
            full.fullData.filter { it.arcid !in full.archives }
        val members = ordered.map {
            it.toArchive(sourceProfileId = conn.profileId, sourceBaseUrl = conn.baseUrl)
        }
        return Tankoubon(full.id, full.name, full.archives, full.summary, tags, full.progress) to members
    }

    /**
     * Whether the tank has a generated cover. False (202) also queues its
     * generation server-side, so never ask for an EMPTY tank.
     */
    suspend fun hasCover(conn: ServerConnection, tankId: String): Boolean =
        LRRTankoubonApi.hasTankThumbnail(conn.client, conn.baseUrl, tankId)

    /** Renames the tank through the metadata PUT. */
    suspend fun rename(conn: ServerConnection, tankId: String, name: String) {
        LRRTankoubonApi.renameTankoubon(conn.client, conn.baseUrl, tankId, name)
    }

    /** Writes summary and tags. The member list is never sent: an empty one would WIPE the tank. */
    suspend fun updateMeta(conn: ServerConnection, tankId: String, summary: String, tags: String) {
        LRRTankoubonApi.updateTankoubon(conn.client, conn.baseUrl, tankId, summary = summary, tags = tags)
    }

    /** Writes the tank's whole tag string, and nothing else. */
    suspend fun setTags(conn: ServerConnection, tankId: String, tags: String) {
        LRRTankoubonApi.updateTankoubon(conn.client, conn.baseUrl, tankId, tags = tags)
    }

    /** Names of the STATIC categories holding the tank, in server order; dynamic ones never count. */
    suspend fun staticCategoryNames(conn: ServerConnection, tankId: String): List<String> =
        LRRCategoryApi.categoriesContaining(conn.client, conn.baseUrl, tankId)
            .filter { !it.isDynamic() }
            .mapNotNull { it.name }

    /** Makes the 1-indexed [globalPage1] the tank's cover. Only the server write: no stamp, no choice. */
    suspend fun setCover(conn: ServerConnection, tankId: String, globalPage1: Int) {
        LRRTankoubonApi.updateTankThumbnail(conn.client, conn.baseUrl, tankId, globalPage1)
    }

    /** Removes [arcid] from the tank. Only the membership call: the caller runs the follow-ups. */
    suspend fun removeMember(conn: ServerConnection, tankId: String, arcid: String) {
        LRRTankoubonApi.removeFromTankoubon(conn.client, conn.baseUrl, tankId, arcid)
    }

    /** What the tank picker sheet shows: every tank, and the tanks one archive is in. */
    data class PickerList(val tanks: List<Tankoubon>, val memberIds: Set<String>)

    /**
     * The picker sheet's load: [arcid]'s current tanks (reverse lookup, when
     * given), then the whole list with [loadAll]'s page loop, kept in
     * [TankListCache]. No cover re-keying and no membership sync: the sheet
     * only reads. Only the reverse lookup is a 0.9.8-only route, so success
     * with an [arcid] marks the server supported in [TankoubonSupportGate].
     */
    suspend fun loadForPicker(conn: ServerConnection, arcid: String?): PickerList {
        val memberIds = if (arcid != null) {
            LRRTankoubonApi.getArchiveTankoubons(conn.client, conn.baseUrl, arcid).toSet()
        } else {
            emptySet()
        }
        val tanks = fetchPages(conn)
        if (arcid != null) TankoubonSupportGate.markSupported(conn.baseUrl)
        TankListCache.put(conn.baseUrl, tanks)
        return PickerList(tanks, memberIds)
    }

    /**
     * The tanks [arcid] is in, for the detail page's row; empty when the
     * server answered and it is in none. Failures propagate, including the
     * 404 of a server without the route: the caller decides what that means.
     */
    suspend fun tanksContaining(conn: ServerConnection, arcid: String): List<Tankoubon> {
        val client = conn.client
        val serverUrl = conn.baseUrl
        val ids = LRRTankoubonApi.getArchiveTankoubons(client, serverUrl, arcid)
        // The reverse lookup is a 0.9.8-only route: answering at all
        // proves support, even with an empty membership.
        TankoubonSupportGate.markSupported(serverUrl)
        if (ids.isEmpty()) return emptyList()
        // id → name/count via the list endpoint, through a short-lived
        // per-server cache so each detail open doesn't page the whole
        // tank list again.
        val idSet = ids.toSet()
        val all = TankListCache.get(client, serverUrl)
        val found = all.filter { it.id in idSet }
        // A tank created since the cached copy: refresh once.
        return if (found.size < idSet.size) {
            TankListCache.invalidate(serverUrl)
            TankListCache.get(client, serverUrl).filter { it.id in idSet }
        } else {
            found
        }
    }

    /** GET /api/tankoubons page by page (0-based) until all `total` entries are held. */
    private suspend fun fetchPages(conn: ServerConnection): List<Tankoubon> {
        val all = mutableListOf<Tankoubon>()
        var page = 0
        while (page < MAX_PAGES) {
            val r = LRRTankoubonApi.getTankoubons(conn.client, conn.baseUrl, page)
            all.addAll(r.result)
            if (r.result.isEmpty() || all.size >= r.total) break
            page++
        }
        return all
    }

    private fun syncMembership(profileId: Long, serverUrl: String, tanks: List<Tankoubon>) {
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
        tanks: List<Tankoubon>,
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

    /** PUTs [order] as the tank's whole member order: a reorder, or the undo of [autoSort]. */
    suspend fun putOrder(conn: ServerConnection, tankId: String, order: List<String>) {
        LRRTankoubonApi.updateTankoubon(conn.client, conn.baseUrl, tankId, archives = order)
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

    /** One member's metadata from its source server, for a fill whose group id has no download row. */
    suspend fun memberArchive(conn: ServerConnection, arcid: String): Archive =
        LRRArchiveApi.getArchiveMetadata(conn.client, conn.baseUrl, arcid)
            .toArchive(sourceProfileId = conn.profileId, sourceBaseUrl = conn.baseUrl)

    /** Creates an empty tank named [name]; returns its id. */
    suspend fun create(conn: ServerConnection, name: String): String {
        val id = LRRTankoubonApi.createTankoubon(conn.client, conn.baseUrl, name)
        TankListCache.invalidate(conn.baseUrl)
        return id
    }

    /**
     * [create] on [background], so a closing screen or a rotation cannot cut
     * the write off. [onResult] runs on that scope's thread with the new id,
     * or with the exception that stopped it (a locked server surfaces as
     * `LRRHttpException` 423).
     */
    fun createAsync(conn: ServerConnection, name: String, onResult: (Result<String>) -> Unit): Job =
        background.launch {
            val result = try {
                Result.success(create(conn, name))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
            onResult(result)
        }

    /**
     * What [delete] does around the server DELETE: clear the tank from its
     * static categories, forget the remembered cover choice, dissolve the
     * downloaded-tank grouping. Production defaults; tests record the calls.
     */
    class DeleteSteps(
        val clearCategories: suspend (
            client: OkHttpClient,
            baseUrl: String,
            tankId: String,
            tankName: String,
        ) -> Boolean =
            { c, url, id, name -> TankCategorySyncer.onDissolve(c, url, id, name) },
        val forgetCoverChoice: (String) -> Unit = { TankCoverChoiceStore.default.remove(it) },
        val dissolveDownloadGroup: (String) -> Unit =
            { ServiceRegistry.dataModule.downloadManager.dissolveTankGroupAsync(it) },
    )

    /**
     * Deletes the tank (never its archives), the one sequence behind the
     * tank detail page, the member management page and the list's batch
     * delete. Upstream leaves the deleted id dangling in static categories
     * (spec 2026-09-22 §6): cleared first, while the tank still exists. After
     * the DELETE: [deleted] (the lists and any page left open on the tank
     * drop it), the cover choice is forgotten and a downloaded-tank grouping
     * dissolves; member downloads reappear standalone (files untouched).
     * Runs on [background]: a caller that goes away mid-way never leaves the
     * categories cleared but the tank alive, nor a deleted tank with its
     * download group (#106). Throws when the DELETE fails; nothing local
     * changes then.
     *
     * @param name the tank's display name, for a failed category write's report
     */
    suspend fun delete(conn: ServerConnection, tankId: String, name: String) {
        background.async {
            val c = conn.client
            val serverUrl = conn.baseUrl
            deleteSteps.clearCategories(c, serverUrl, tankId, name)
            LRRTankoubonApi.deleteTankoubon(c, serverUrl, tankId)
            deleted(serverUrl, tankId)
            deleteSteps.forgetCoverChoice(tankId)
            deleteSteps.dissolveDownloadGroup(tankId)
        }.await()
    }

    /**
     * After a successful DELETE: drops [baseUrl]'s [TankListCache] and
     * posts [ArchiveDeletedEvent] with the tank id, so the gallery list and a
     * tank detail page left underneath stop showing the tank (#98).
     */
    fun deleted(baseUrl: String, tankId: String) {
        TankListCache.invalidate(baseUrl)
        AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(tankId))
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
