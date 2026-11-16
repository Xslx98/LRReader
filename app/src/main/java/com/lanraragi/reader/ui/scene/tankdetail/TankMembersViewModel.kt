package com.lanraragi.reader.ui.scene.tankdetail

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.cover.TankCoverCacheStamp
import com.lanraragi.reader.reading.TankPageMath
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.server.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import com.lanraragi.reader.tankoubon.TankCategorySyncer
import com.lanraragi.reader.tankoubon.TankMemberOrderOps
import com.lanraragi.reader.tankoubon.TankRepository
import com.lanraragi.reader.tankoubon.TankTagSyncer
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for [TankMembersScene]. Loads a single tankoubon's member
 * archives (with full metadata) from its SOURCE server, exposes the
 * global-page math inputs ([memberIds] / [pageOffsets]) the scene needs
 * for the read-from-start / continue-reading entries, and hosts the
 * management ops (rename / metadata / delete / member remove / reorder /
 * cover).
 *
 * The Scene observes [tankName] / [members] / [progress] / [isLoading]
 * for state and [uiEvent] for one-shot messages. View construction,
 * adapter, and navigation remain in the Scene.
 */
class TankMembersViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Identity (set once by init)
    // -------------------------------------------------------------------------

    /** LANraragi tank id (TANK_-prefixed); set once by [init]. */
    var tankId: String = ""
        private set

    /** Source profile that owns this tank; set once by [init]. */
    var profileId: Long = 0L
        private set

    /**
     * What the last [load] learnt about the tank besides its members, as one
     * value (it replaces six volatile fields that were written one by one).
     *
     * @property baseUrl resolved from [profileId] by the first [load]; null before.
     * @property summary tank summary from the last load; null when the server sent none.
     * @property tags tank tags from the last load; null when the server sent none.
     * @property metaLoaded a load succeeded, so [summary]/[tags] hold server
     *   truth. Gates the edit-metadata menu item: submitting the dialog's
     *   empty defaults before a successful load would WIPE them server-side.
     * @property coverFallbackMember FIRST member to render as the cover
     *   stand-in when the cover probe reported no generated cover (the probe
     *   also queues generation); null = a real cover exists (or the tank is
     *   empty / the probe failed). Cleared by a successful [setCover].
     */
    data class TankMeta(
        val baseUrl: String? = null,
        val summary: String? = null,
        val tags: String? = null,
        val metaLoaded: Boolean = false,
        val coverFallbackMember: Archive? = null,
    )

    private val _meta = MutableStateFlow(TankMeta())

    /** See [TankMeta]; the scene re-enables the edit-metadata item when it changes. */
    val meta: StateFlow<TankMeta> = _meta.asStateFlow()

    val baseUrl: String? get() = _meta.value.baseUrl
    val summary: String? get() = _meta.value.summary
    val tags: String? get() = _meta.value.tags
    val metaLoaded: Boolean get() = _meta.value.metaLoaded
    val coverFallbackMember: Archive? get() = _meta.value.coverFallbackMember

    private var initialized = false

    // -------------------------------------------------------------------------
    // Tank state
    // -------------------------------------------------------------------------

    private val _tankName = MutableStateFlow("")

    /** Tank display name; seeded from the nav arg, refreshed by [load]. */
    val tankName: StateFlow<String> = _tankName.asStateFlow()

    private val _state = MutableStateFlow<ListScreenState<Archive>>(ListScreenState.Loading)

    /**
     * The member list screen: the ordered member archives (server order,
     * mapped with source context), or loading / empty / the last load's
     * error. Its items are the ONE holder of the member order; [memberIds]
     * and [pageOffsets] are read from them.
     */
    val state: StateFlow<ListScreenState<Archive>> = _state.asStateFlow()

    /** The ordered members ([state]'s items). */
    val members: List<Archive> get() = _state.value.items

    private fun on(event: ListScreenState.Event<Archive>) {
        _state.update { ListScreenState.reduce(it, event) }
    }

    private val _progress = MutableStateFlow(0)

    /** Global 1-indexed reading progress; 0/1 = nothing meaningful. */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Ordered member arcids, read from [members]. */
    val memberIds: List<String> get() = members.map { it.arcid }

    /** Prefix-sum global page offsets of [members], see [TankPageMath.pageOffsets]. */
    val pageOffsets: List<Int> get() = TankPageMath.pageOffsets(members.map { it.pagecount })

    // -------------------------------------------------------------------------
    // One-shot UI events
    // -------------------------------------------------------------------------

    private val _uiEvent = MutableSharedFlow<TankDetailUiEvent>(extraBufferCapacity = 8)

    /** One-shot events for the scene (errors / unsupported server). */
    val uiEvent: SharedFlow<TankDetailUiEvent> = _uiEvent.asSharedFlow()

    /**
     * Sealed interface for one-shot UI events emitted by this ViewModel.
     * The Scene observes [uiEvent] and dispatches via `when`.
     */
    sealed interface TankDetailUiEvent {
        /**
         * A failure the screen does not show by itself: a management op, or a
         * load while members are shown (a load with none is the [state]'s
         * error, including "the server lacks the tankoubon routes").
         */
        data class ShowError(val message: String) : TankDetailUiEvent

        /** A management op succeeded; the scene toasts [messageResId]. */
        data class ShowSuccess(val messageResId: Int) : TankDetailUiEvent

        /** The tank was deleted server-side; the scene closes itself. */
        data object Deleted : TankDetailUiEvent

        /**
         * An automatic reorder (sort by title / reverse) was persisted;
         * [previousOrder] is what an undo should PUT back.
         */
        data class OrderApplied(val previousOrder: List<String>) : TankDetailUiEvent
    }

    // -------------------------------------------------------------------------
    // API operations
    // -------------------------------------------------------------------------

    /**
     * Applies the nav args. Idempotent: only the FIRST call wins, so a view
     * recreation over a retained ViewModel keeps the loaded state intact.
     * Seeds [tankName] from the nav arg for an instant toolbar title.
     */
    /**
     * Membership follow seam (spec 2026-09-21 §1/§3): a successful detail
     * fetch hands the server's member list to [TankMembershipSync].
     * Replaceable for tests; the default no-ops outside a live app.
     */
    internal var membershipSync: TankMembershipSyncFactory.Runner = TankMembershipSyncFactory.runnerSafely()

    /** Source base-URL resolution seam (production = [resolveSourceBaseUrl]); replaceable for tests. */
    /** Remembered cover choice (spec 2026-09-22-tank-cover §4); injectable for tests. */
    internal var coverChoices: TankCoverChoiceStore = TankCoverChoiceStore.default

    /** Delay before the second best-effort cover re-apply (Minion dequeues every ~5 s). */
    internal var coverReapplyDelayMs: Long = COVER_REAPPLY_DELAY_MS

    /** Tank tag materialization seams (spec 2026-09-22 §5.2 "remove"); replaceable for tests. */
    internal var tagSyncSnapshot: suspend (OkHttpClient, String, String) -> TankTagSyncer.Snapshot? =
        { client, url, id -> TankTagSyncer.snapshot(client, url, id) }
    internal var tagSyncAfterRemove: suspend (OkHttpClient, String, TankTagSyncer.Snapshot, List<String>) -> Boolean =
        { client, url, snap, removed -> TankTagSyncer.afterRemove(client, url, snap, removed) }

    /** Static-category promotion seam (spec 2026-09-22 §6); replaceable for tests. */
    internal var categorySyncAfterRemove: suspend (OkHttpClient, String, TankTagSyncer.Snapshot, List<String>) -> Boolean =
        { client, url, snap, removed -> TankCategorySyncer.afterRemove(client, url, snap, removed) }

    /** Downloaded-tank grouping follow-ups (ADR-004); replaceable for tests. */
    internal var untagDownloadMember: (String, String) -> Unit =
        { id, arcid -> ServiceRegistry.dataModule.downloadManager.untagTankMemberAsync(id, arcid) }

    internal var baseUrlResolver: suspend (Long) -> String = { id ->
        resolveSourceBaseUrl(id, ServiceRegistry.dataModule.profileLookupCache)
    }

    /** The tank's server reads and writes, and its delete sequence. Its list-load membership sync is unused here. */
    internal var tanks = TankRepository(TankMembershipSyncFactory.runnerSafely()::sync)

    /**
     * The tank's source server: [profileId] at [url]. Built from the
     * [baseUrlResolver] result rather than `ServerConnections.forProfile`,
     * so tests can point the resolver at a mock.
     */
    private fun connection(url: String) = ServerConnection(profileId, url, ServiceRegistry.networkModule.okHttpClient)

    private fun syncMembership(url: String, name: String, memberIds: List<String>, progress: Int, pagecount: Int) {
        val truth = listOf(TankMembershipSync.TankTruth(tankId, name, memberIds, progress, pagecount))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                membershipSync.sync(profileId, url, truth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank membership sync failed")
            }
        }
    }

    fun init(tankId: String, name: String, profileId: Long) {
        if (initialized) return
        initialized = true
        this.tankId = tankId
        this.profileId = profileId
        _tankName.value = name
    }

    /**
     * Fetches the tank's full member metadata from its source server and
     * publishes name/progress/members/metadata. A 404 flips
     * [TankoubonSupportGate] and fails with the "unsupported" message; a
     * failure is the [state]'s error, also toasted while members are shown.
     */
    fun load() {
        on(ListScreenState.Event.Load)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = baseUrlResolver(profileId)
                _meta.update { it.copy(baseUrl = url) }
                val conn = connection(url)
                // Members in `archives` order, mapped with the EXPLICIT source
                // context (this tank may belong to a non-active profile).
                val (tank, mapped) = tanks.detail(conn, tankId)
                val fallback = resolveCoverFallback(conn, mapped)
                _meta.update {
                    it.copy(summary = tank.summary, tags = tank.tags, metaLoaded = true, coverFallbackMember = fallback)
                }
                // Fresh server truth — revalidate the cover. Must precede the
                // publications below: their collectors re-bind the cover and
                // must already see the new stamp.
                TankCoverCacheStamp.revalidate(tankId, tank.archives)
                _tankName.value = tank.name
                _progress.value = tank.progress
                on(ListScreenState.Event.Loaded(mapped))
                syncMembership(url, tank.name, tank.archives, tank.progress, mapped.sumOf { it.pagecount })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val ctx = ServiceRegistry.appModule.getContext()
                val url = baseUrl
                val message = if (url != null && TankoubonSupportGate.markFrom(url, e)) {
                    ctx.getString(R.string.tankoubons_unsupported)
                } else {
                    Log.e(TAG, "Failed to load tankoubon", e)
                    errorMessage(ctx, e)
                }
                on(ListScreenState.Event.Failed(message))
                // With members shown the error keeps them: report it.
                if (members.isNotEmpty()) _uiEvent.tryEmit(TankDetailUiEvent.ShowError(message))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Management ops
    // -------------------------------------------------------------------------

    /** Renames the tank, then reloads server truth. */
    fun rename(name: String) = mutateAndReload { conn ->
        tanks.rename(conn, tankId, name)
    }

    /** Updates summary/tags (never the member list), then reloads. */
    fun editMeta(summary: String, tags: String) = mutateAndReload { conn ->
        tanks.updateMeta(conn, tankId, summary, tags)
    }

    /** Removes [arcid] from the tank, then reloads. */
    fun removeMember(arcid: String) = removeMembers(listOf(arcid))

    /** Removes every id in [arcids] (one DELETE each), then reloads once. */
    fun removeMembers(arcids: List<String>) {
        val firstBefore = memberIds.firstOrNull()
        val remaining = members.filter { it.arcid !in arcids }
        mutateAndReload { conn ->
            val client = conn.client
            val url = conn.baseUrl
            // Rule 3 (spec 2026-09-22 §5.2) needs the pre-removal tank + member tags.
            val before = tagSyncSnapshot(client, url, tankId)
            for (arcid in arcids) {
                tanks.removeMember(conn, tankId, arcid)
                // Downloaded-tank grouping follows: the member's download row (if
                // any) reappears as a standalone download.
                untagDownloadMember(tankId, arcid)
            }
            if (before != null) {
                tagSyncAfterRemove(client, url, before, arcids)
                categorySyncAfterRemove(client, url, before, arcids)
            }
            val remainingIds = remaining.map { it.arcid }
            if (firstBefore != null && firstBefore in arcids) {
                // Removing the first member queues a cover regeneration upstream.
                reapplyCoverChoice(conn, remainingIds, TankPageMath.pageOffsets(remaining.map { it.pagecount }))
            } else {
                coverChoices.reconcile(tankId, remainingIds)
            }
        }
    }

    /**
     * Puts a remembered cover choice back after one of the app's own writes
     * that makes upstream regenerate the cover (first member, page 1): once
     * now and once after [coverReapplyDelayMs], because the regeneration
     * job runs asynchronously and may land after the first re-PUT. Best
     * effort — failures are silent (cosmetic). No-op without a choice, or
     * when the chosen archive is no longer a member (the choice is dropped).
     */
    private suspend fun reapplyCoverChoice(
        conn: ServerConnection,
        memberIds: List<String>,
        offsets: List<Int>,
    ) {
        val choice = coverChoices.reconcile(tankId, memberIds) ?: return
        val index = memberIds.indexOf(choice.arcid)
        if (index < 0) return
        val page1 = TankPageMath.globalPage1(offsets, index, choice.page0)
        putCoverQuietly(conn, page1)
        viewModelScope.launch(Dispatchers.IO) {
            delay(coverReapplyDelayMs)
            putCoverQuietly(conn, page1)
        }
    }

    private suspend fun putCoverQuietly(conn: ServerConnection, page1: Int) {
        try {
            tanks.setCover(conn, tankId, page1)
            TankCoverCacheStamp.bump(tankId)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Cosmetic: the membership write already succeeded; the next
            // reorder or an explicit set-cover retries.
        }
    }

    /**
     * Deletes the tank server-side. Success surfaces
     * [TankDetailUiEvent.Deleted] (no reload / no success toast — the
     * Deleted event carries the outcome and closes the scene).
     */
    fun deleteTank() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                tanks.delete(connection(url), tankId, _tankName.value)
                _uiEvent.tryEmit(TankDetailUiEvent.Deleted)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emitError(e)
            }
        }
    }

    /**
     * Sets the tank cover to page [page0] (0-indexed) of member
     * [memberIndex] (1-indexed global page at the API boundary). The server
     * may answer 200 with a queued Minion job before the thumbnail actually
     * exists — cosmetic, still treated as success. Bumps
     * [TankCoverCacheStamp] so every cover bind (this scene AND the tank
     * list) bypasses the stale cached image, and remembers the choice so
     * the app's own reorders can put it back (see [reapplyCoverChoice]).
     */
    fun setCover(memberIndex: Int, page0: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            // offsets has size members+1; valid member indices are 0..size-2
            val offsets = pageOffsets
            if (memberIndex < 0 || memberIndex >= offsets.size - 1 || page0 < 0) return@launch
            try {
                val page1 = TankPageMath.globalPage1(offsets, memberIndex, page0)
                tanks.setCover(connection(url), tankId, page1)
                // The cover now exists server-side — drop the stand-in.
                _meta.update { it.copy(coverFallbackMember = null) }
                TankCoverCacheStamp.bump(tankId)
                members.getOrNull(memberIndex)?.let { member ->
                    coverChoices.put(tankId, TankCoverChoiceStore.Choice(member.arcid, page0, profileId))
                }
                _uiEvent.tryEmit(TankDetailUiEvent.ShowSuccess(R.string.tank_cover_updated))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emitError(e)
            }
        }
    }

    /**
     * Persists a new member order (the scene has already applied it
     * optimistically to its adapter). The [state]'s members (and with them
     * [memberIds] / [pageOffsets]) take the new order BEFORE the network
     * call: the state is conflated on equality, so a failed call's [load]
     * rollback only re-renders if it genuinely holds the new order — and
     * the continue-reading math needs the reordered pagecounts either way. On failure the server
     * truth is re-fetched (rollback) after a [R.string.tank_reorder_failed]
     * (or 423 locked) message.
     *
     * [newOrder] is reconciled against CURRENT membership first — a reload
     * can land mid-drag, so ids that no longer exist are dropped and ids
     * the drag snapshot didn't know about are appended (matching the
     * server's append-at-end semantics).
     */
    fun reorder(newOrder: List<String>) = applyOrder(newOrder, undoable = false)

    /** Sort by title (spec 2026-09-21 §2): episode-aware key over the current titles; undoable. */
    fun sortByTitle() {
        val titles = members.associate { it.arcid to it.title }
        applyOrder(TankMemberOrderOps.sortByTitle(memberIds) { titles[it].orEmpty() }, undoable = true)
    }

    /** Reverse order: the whole member order reversed; undoable. */
    fun reverseOrder() = applyOrder(TankMemberOrderOps.reverse(memberIds), undoable = true)

    /**
     * Persists [newOrder] (see [reorder] for the reconcile + rollback
     * contract). [undoable] = an automatic action: a no-op order surfaces
     * [R.string.tank_already_sorted], success emits
     * [TankDetailUiEvent.OrderApplied] with the previous order for the undo
     * Snackbar. After a successful PUT the membership follow seam is fed the
     * new order so downloaded group rows follow without waiting for a reload.
     */
    fun applyOrder(newOrder: List<String>, undoable: Boolean) {
        val currentIds = memberIds
        val currentSet = currentIds.toSet()
        val newOrderSet = newOrder.toSet()
        val finalOrder =
            newOrder.filter { it in currentSet } + currentIds.filter { it !in newOrderSet }
        if (finalOrder == memberIds) {
            if (undoable) _uiEvent.tryEmit(TankDetailUiEvent.ShowSuccess(R.string.tank_already_sorted))
            return
        }
        val previousOrder = memberIds
        val byId = members.associateBy { it.arcid }
        val reordered = finalOrder.mapNotNull { byId[it] }
        val ids = reordered.map { it.arcid }
        on(ListScreenState.Event.Loaded(reordered))
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                val conn = connection(url)
                tanks.putOrder(conn, tankId, ids)
                if (undoable) _uiEvent.tryEmit(TankDetailUiEvent.OrderApplied(previousOrder))
                // Upstream queued a cover regeneration from the new first member.
                reapplyCoverChoice(conn, ids, TankPageMath.pageOffsets(reordered.map { it.pagecount }))
                syncMembership(url, _tankName.value, ids, _progress.value, reordered.sumOf { it.pagecount })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val ctx = ServiceRegistry.appModule.getContext()
                val message = if (e is LRRHttpException && e.code == HTTP_LOCKED) {
                    errorMessage(ctx, e)
                } else {
                    ctx.getString(R.string.tank_reorder_failed)
                }
                _uiEvent.tryEmit(TankDetailUiEvent.ShowError(message))
                // Roll back locally first (the server may be unreachable, in
                // which case the reload below cannot restore anything), then
                // re-fetch server truth.
                restoreOrder(previousOrder)
                load()
            }
        }
    }

    /** Puts the [members] back to [order] without a network call. */
    private fun restoreOrder(order: List<String>) {
        val byId = members.associateBy { it.arcid }
        val restored = order.mapNotNull { byId[it] } + members.filter { it.arcid !in order }
        on(ListScreenState.Event.Loaded(restored))
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Shared scaffold for the simple mutate ops (rename / editMeta /
     * removeMember): IO launch, [baseUrl] guard, success toast + [load]
     * reload, [errorMessage]-mapped failure.
     */
    private fun mutateAndReload(op: suspend (conn: ServerConnection) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = requireBaseUrl() ?: return@launch
            try {
                op(connection(url))
                _uiEvent.tryEmit(TankDetailUiEvent.ShowSuccess(R.string.tank_op_done))
                load()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emitError(e)
            }
        }
    }

    /**
     * Probe for a generated cover; a missing one (202 — the probe also
     * queues server-side generation) elects [members]' first entry as the
     * stand-in. Probe failure or an empty tank keep the normal tank route.
     */
    private suspend fun resolveCoverFallback(conn: ServerConnection, members: List<Archive>): Archive? {
        val first = members.firstOrNull() ?: return null
        val hasCover = try {
            tanks.hasCover(conn, tankId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ignored: Exception) {
            true
        }
        return if (hasCover) null else first
    }

    /**
     * [baseUrl] is only null while no [load] ever resolved the source
     * profile (resolution itself failed). Ops can't proceed then — surface
     * a generic error instead of silently dropping the action.
     */
    private fun requireBaseUrl(): String? {
        val url = baseUrl
        if (url == null) {
            val ctx = ServiceRegistry.appModule.getContext()
            _uiEvent.tryEmit(TankDetailUiEvent.ShowError(ctx.getString(R.string.error_unknown)))
        }
        return url
    }

    private fun emitError(e: Exception) {
        val ctx = ServiceRegistry.appModule.getContext()
        _uiEvent.tryEmit(TankDetailUiEvent.ShowError(errorMessage(ctx, e)))
    }

    /**
     * 423 (locked) gets a dedicated message — the server is busy regenerating
     * the tank; everything else goes through the shared [friendlyError] map.
     * The gallery's tank mode maps it the same way ([com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene]).
     */
    private fun errorMessage(context: Context, e: Exception): String =
        if (e is LRRHttpException && e.code == HTTP_LOCKED) {
            context.getString(R.string.tank_locked)
        } else {
            friendlyError(context, e)
        }

    private companion object {
        const val COVER_REAPPLY_DELAY_MS = 6_000L
        const val HTTP_LOCKED = 423
        const val TAG = "TankMembersViewModel"
    }
}
