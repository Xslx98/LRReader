package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.unifile.UniFile
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.probeSourceHealthy
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Composite reader provider: one session spans EVERY member of a
 * tankoubon as a single continuous page space (spec
 * 2026-08-05-tank-seamless-reading). Global 0-indexed pages map through
 * [TankPageMap] to (member, local page) and are served by per-member
 * [TankMemberSource]s — locally-downloaded members from disk, the rest
 * streamed — resolved lazily via [TankMemberRouting].
 *
 * Progress is TANK-ONLY bookkeeping: the local save keys the tank id in
 * the shared reading_progress store and the server sync PUTs the tank's
 * global progress (per-member archive progress is deliberately never
 * written — members are hidden from grouped listings anyway).
 *
 * Size is published from metadata pagecounts immediately (fast open);
 * when a member's real file list disagrees, [TankPageMap.correct] remaps
 * in place and the GL layer is re-notified.
 */
class TankGalleryProvider(
    context: Context,
    private val seed: TankSessionSeed,
) : GalleryProvider2() {

    private val appContext = context.applicationContext

    private val pageMap = TankPageMap(seed.members.map { it.pagecount })

    /**
     * Live member slots, index-aligned with [pageMap]'s members. Slots are
     * only ever REMOVED (confirmed server-side deletion), never reordered.
     * Guarded by [slotsLock] together with the map mutation itself.
     */
    private val slots: MutableList<MemberSlot> =
        seed.members.mapTo(ArrayList(seed.members.size)) { MemberSlot(it) }
    private val slotsLock = Any()

    private class MemberSlot(val seed: TankMemberSeed) {
        @Volatile
        var source: TankMemberSource? = null
        val sourceMutex = Mutex()

        /** True once the source's real file list corrected the page map. */
        @Volatile
        var counted = false
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var providerScope: CoroutineScope? = null

    @Volatile
    private var errorMessage: String? = null

    @Volatile
    private var errorState = false

    private val serverUrlDeferred = CompletableDeferred<String?>()

    private val inflightRequests = ConcurrentHashMap<Int, Boolean>()
    private val rebindWanted = ConcurrentHashMap.newKeySet<Int>()

    /** Global cap on concurrent prefetch downloads (mirrors LRRGalleryProvider). */
    private val prefetchSemaphore = Semaphore(PREFETCH_PARALLELISM)

    /** Global 0-indexed start page: SP save for this tank, else the seed's server progress. */
    @Volatile
    private var startPageValue: Int =
        loadReadingProgress(appContext, seed.tankId).takeIf { it > 0 }
            ?: 0

    @Volatile
    private var startPageBaseline = 0

    @Volatile
    private var userNavigated = false

    /** Serialized + conflated tank global progress sync (app-scoped, survives stop()). */
    private val progressSyncer = ReadingProgressSyncer(
        ServiceRegistry.coroutineModule.ioScope
    ) sync@{ page0 ->
        val url = serverUrlDeferred.await() ?: return@sync
        LRRTankoubonApi.updateTankProgress(
            ServiceRegistry.networkModule.okHttpClient, url, seed.tankId, page0 + 1
        )
    }

    // ==================== Lifecycle ====================

    override fun start() {
        super.start()
        userNavigated = false
        startPageBaseline = startPageValue

        if (seed.tankId.isEmpty() || seed.members.isEmpty() || pageMap.total <= 0) {
            errorState = true
            errorMessage = GetText.getString(R.string.error_empty)
            notifyDataChanged()
            return
        }

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + ServiceRegistry.coroutineModule.exceptionHandler
        )
        providerScope = scope

        // Resolve the tank's source-profile base URL once, app-scoped so a
        // quick stop() can't strand the progress syncer awaiting it.
        ServiceRegistry.coroutineModule.ioScope.launch {
            if (!serverUrlDeferred.isCompleted) {
                val url = try {
                    resolveSourceBaseUrl(
                        seed.profileId, ServiceRegistry.dataModule.profileLookupCache
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "source profile resolve failed for tank=${seed.tankId}: ${e.message}")
                    }
                    null
                }
                serverUrlDeferred.complete(url)
            }
        }

        // Metadata offsets make the size known RIGHT NOW — publish it so the
        // reader lays out instantly; the entry member's real file list warms
        // in the background and corrects the map if needed.
        notifyDataChanged()
        scope.launch {
            val entry = (if (initialPageOverride >= 0) initialPageOverride else startPageValue)
                .coerceIn(0, pageMap.total - 1)
            pageMap.locate(entry)?.let { (member, _) ->
                runCatching { countedSourceFor(member) }
            }
        }
    }

    override fun stop() {
        super.stop()
        stopped = true
        inflightRequests.clear()
        rebindWanted.clear()
        providerScope?.cancel()
        providerScope = null
        synchronized(slotsLock) { slots.forEach { it.source?.cancelAll() } }
        ServiceRegistry.coroutineModule.ioScope.launch {
            ReaderPageCache.cleanupOldCaches(appContext)
        }
    }

    override fun size(): Int = if (errorState) GalleryProvider.STATE_ERROR else pageMap.total

    override fun getError(): String = errorMessage ?: "Unknown error"

    // ==================== Progress (tank-only) ====================

    override fun getStartPage(): Int = startPageValue

    override fun putStartPage(page: Int) {
        if (page != startPageBaseline) userNavigated = true
        startPageValue = page
        saveReadingProgress(appContext, seed.tankId, page)
        progressSyncer.submit(page)
    }

    // ==================== Requests ====================

    override fun onRequest(index: Int) {
        if (stopped) return
        if (pageMap.locate(index) == null) {
            notifyPageFailed(index, GetText.getString(R.string.error_out_of_range))
            return
        }
        if (inflightRequests.putIfAbsent(index, true) != null) {
            rebindWanted.add(index)
            return
        }
        notifyPageWait(index)
        val scope = providerScope ?: run {
            inflightRequests.remove(index)
            return
        }
        scope.launch {
            var cancelled = false
            try {
                serveGlobalPage(index)
            } catch (ignored: TankPageCancelledException) {
                cancelled = true
            } catch (ignored: MemberDeletedException) {
                // The remap's notifyDataChanged re-drives layout; whatever
                // page now occupies this slot is re-requested by the view.
                cancelled = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Failed to serve tank page $index: ${e.message}")
                notifyPageFailed(index, friendlyPageError(e))
            } finally {
                inflightRequests.remove(index)
                if (rebindWanted.remove(index) && cancelled && !stopped) {
                    onRequest(index)
                }
            }
        }
    }

    override fun onForceRequest(index: Int) {
        inflightRequests.remove(index)
        onRequest(index)
    }

    override fun onCancelRequest(index: Int) {
        val (member, local) = pageMap.locate(index) ?: return
        slotAt(member)?.source?.cancelPage(local)
    }

    /**
     * Resolve member + source, correct the map from the real file list on
     * first touch, then fetch + decode + publish. Runs on the provider IO
     * scope; every notify goes out at the GLOBAL index.
     */
    private suspend fun serveGlobalPage(global0: Int) {
        var located = pageMap.locate(global0)
            ?: run {
                notifyPageFailed(global0, GetText.getString(R.string.error_out_of_range))
                return
            }
        var source = countedSourceFor(located.first)
        // The correction may have shifted this global page onto a different
        // member (or out of range). Re-locate once against the fresh map.
        pageMap.locate(global0)?.let { fresh ->
            if (fresh != located) {
                located = fresh
                source = countedSourceFor(located.first)
            }
        } ?: run {
            notifyPageFailed(global0, GetText.getString(R.string.error_out_of_range))
            return
        }

        val image = source.obtainImage(located.second) { pct ->
            notifyPagePercent(global0, pct)
        }
        if (stopped) {
            image?.recycle()
            return
        }
        if (image != null) {
            notifyPageSucceed(global0, image)
            prefetchAround(global0)
        } else {
            notifyPageFailed(global0, GetText.getString(R.string.error_decoding_failed))
        }
    }

    /**
     * Warm the neighbourhood of [global0] in BOTH directions across member
     * boundaries: resolving a neighbour's counted source is exactly the
     * boundary file-list prefetch (spec decision 5), and page-byte warms
     * ride the same walk. Downloads are bounded by [prefetchSemaphore];
     * every step is best-effort.
     */
    private fun prefetchAround(global0: Int) {
        val scope = providerScope ?: return
        scope.launch {
            val targets =
                (global0 + 1..global0 + PREFETCH_FORWARD) + (global0 - PREFETCH_BACKWARD until global0)
            for (target in targets) {
                if (stopped) break
                val (member, local) = pageMap.locate(target) ?: continue
                if (hasCache(target)) continue
                launch {
                    prefetchSemaphore.withPermit {
                        if (stopped) return@withPermit
                        try {
                            countedSourceFor(member).prefetchPage(local)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "prefetch failed for global=$target: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The member's source, resolved (routing) and COUNTED (real file list
     * loaded + page map corrected) exactly once. On a count correction the
     * GL layer is re-notified and stale cached entries are dropped.
     */
    private suspend fun countedSourceFor(memberIndex: Int): TankMemberSource {
        val slot = slotAt(memberIndex)
            ?: throw IOException("member $memberIndex vanished")
        slot.source?.let { existing ->
            if (slot.counted) return existing
        }
        slot.sourceMutex.withLock {
            val source = slot.source ?: run {
                val url = serverUrlDeferred.await()
                    ?: throw IOException("No server for tank ${seed.tankId}")
                TankMemberRouting.resolve(
                    appContext, slot.seed, seed.profileId, url,
                    pageClient = ServiceRegistry.networkModule.pageStreamClient,
                    listClient = ServiceRegistry.networkModule.longReadClient,
                ).also { slot.source = it }
            }
            if (!slot.counted) {
                val realCount = try {
                    source.ensurePageCount()
                } catch (e: LRRHttpException) {
                    if (isConfirmedDeleted(e)) {
                        dropMember(slot)
                        throw MemberDeletedException(slot.seed.arcid)
                    }
                    throw e
                }
                val index = indexOfSlot(slot)
                if (index >= 0) {
                    val oldTotal = pageMap.total
                    val changed = pageMap.correct(index, realCount)
                    slot.counted = true
                    if (changed) {
                        onMapRemapped(oldTotal)
                    }
                } else {
                    slot.counted = true
                }
            }
            return source
        }
    }

    /**
     * A member fetch answering 400/404 is only "deleted" when the server
     * itself is demonstrably alive — a reverse proxy fronting a dead
     * backend answers 400/502 too (same disambiguation as the detail
     * page's [probeSourceHealthy] flow). Unconfirmed → treated as a plain
     * failure (inline error page + retry), never a silent skip.
     */
    private suspend fun isConfirmedDeleted(e: LRRHttpException): Boolean {
        if (e.code != HTTP_BAD_REQUEST && e.code != HTTP_NOT_FOUND) return false
        val url = serverUrlDeferred.await() ?: return false
        return probeSourceHealthy(ServiceRegistry.networkModule.okHttpClient, url)
    }

    /**
     * Remove a confirmed-deleted member: its pages leave the global space,
     * later members shift down, the GL layer relays out. Requests in
     * flight against old indices resolve against the fresh map (their
     * serve loop re-locates) or fall out of range harmlessly.
     */
    private fun dropMember(slot: MemberSlot) {
        val oldTotal: Int
        synchronized(slotsLock) {
            val index = slots.indexOf(slot)
            if (index < 0) return
            oldTotal = pageMap.total
            pageMap.removeMember(index)
            slots.removeAt(index)
        }
        slot.source?.cancelAll()
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "member ${slot.seed.arcid} confirmed deleted; remapped tank ${seed.tankId}")
        }
        onMapRemapped(oldTotal)
    }

    /** Quiet-skip marker: the requested page's member vanished; the remap re-drives layout. */
    private class MemberDeletedException(arcid: String) :
        IOException("tank member deleted: $arcid")

    /**
     * After any page-map remap: drop every cached image at or beyond the
     * FIRST index whose mapping may have shifted (conservatively: all of
     * them — remaps are rare, correctness beats cache warmth) and
     * re-notify the GL layer so layout picks up the new size.
     */
    private fun onMapRemapped(oldTotal: Int) {
        val ceiling = maxOf(oldTotal, pageMap.total)
        for (i in 0 until ceiling) {
            removeCache(i)
        }
        notifyDataChanged()
    }

    private fun slotAt(memberIndex: Int): MemberSlot? =
        synchronized(slotsLock) { slots.getOrNull(memberIndex) }

    private fun indexOfSlot(slot: MemberSlot): Int =
        synchronized(slotsLock) { slots.indexOf(slot) }

    private fun friendlyPageError(e: Exception): String = when (e) {
        is IOException -> GetText.getString(R.string.lrr_error_load_pages_failed)
        else -> GetText.getString(R.string.error_decoding_failed)
    }

    // ==================== Member mapping queries ====================
    // Read-only views over the LIVE map for per-member consumers (stamps
    // routing, bookkeeping). Results reflect corrections/removals at call
    // time — callers must treat them as snapshots.

    /** (arcid, member-local page0) of a global page, or null out of range. */
    fun locateMember(global0: Int): Pair<String, Int>? {
        val (member, local) = pageMap.locate(global0) ?: return null
        val arcid = slotAt(member)?.seed?.arcid ?: return null
        return arcid to local
    }

    /** Global 0-indexed page of ([arcid], local [page0]), or null when unmapped. */
    fun globalPageOf(arcid: String, page0: Int): Int? {
        synchronized(slotsLock) {
            val index = slots.indexOfFirst { it.seed.arcid == arcid }
            if (index < 0) return null
            if (page0 < 0 || page0 >= pageMap.pageCountOf(index)) return null
            return pageMap.globalOf(index, page0)
        }
    }

    /** Current member arcids in tank order. */
    fun memberArcids(): List<String> =
        synchronized(slotsLock) { slots.map { it.seed.arcid } }

    // ==================== Save/share ====================

    override fun getImageFilename(index: Int): String {
        val located = pageMap.locate(index)
        val tankPrefix = seed.tankId.takeLast(TANK_ID_SUFFIX_LEN)
        return if (located != null) {
            val arcid = slotAt(located.first)?.seed?.arcid.orEmpty()
            String.format(
                Locale.US, "tank-%s-%s-%04d",
                tankPrefix, arcid.take(ARCID_PREFIX_LEN), located.second + 1
            )
        } else {
            String.format(Locale.US, "tank-%s-%04d", tankPrefix, index + 1)
        }
    }

    override fun save(index: Int, file: UniFile): Boolean {
        val (member, local) = pageMap.locate(index) ?: return false
        return slotAt(member)?.source?.savePage(local, file) ?: false
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val dst = dir.createFile(filename) ?: return null
        return if (save(index, dst)) dst else null
    }

    companion object {
        private const val TAG = "TankGalleryProvider"
        private const val TANK_ID_SUFFIX_LEN = 6
        private const val ARCID_PREFIX_LEN = 8

        /**
         * Forward warm distance (pages). Matches LRRGalleryProvider's
         * PRELOAD_COUNT; near a member's tail it necessarily reaches into
         * the next member, which is what makes the crossing seamless.
         */
        private const val PREFETCH_FORWARD = 5

        /** Backward warm distance so a back-swipe across a boundary is instant. */
        private const val PREFETCH_BACKWARD = 2

        private const val PREFETCH_PARALLELISM = 2

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
    }
}
