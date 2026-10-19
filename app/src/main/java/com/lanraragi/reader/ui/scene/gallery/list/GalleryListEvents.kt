package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankMembershipChangedEvent
import com.lanraragi.reader.util.DetachBuffer
import com.lanraragi.reader.util.collectFlowWhileCreated

/**
 * The app events the gallery list follows for its whole lifetime — download
 * badges, server-side deletions, cover and rating changes, tank membership
 * edits and batch results — and the buffers that hold the ones arriving
 * while the list is covered until [drainOnResume] replays them on screen.
 */
class GalleryListEvents(private val scene: GalleryListScene) {

    /**
     * arcids deleted while this scene was off-screen (covered by the detail page).
     * Drained in [drainOnResume] so the RecyclerView remove + reflow animation plays
     * after the user is actually looking at the list, not while it's hidden.
     */
    private val deletionBuffer = DetachBuffer<String>()

    // Tank membership edits made on the detail page while this list was
    // covered; replayed in onResume so the merge choreography plays on screen.
    private val tankMembershipBuffer = DetachBuffer<TankMembershipChangedEvent>()

    // Batch results (per-item failure dialog + ClearNew badge refresh) that
    // arrived while this list was detached under a pushed scene; replayed in
    // onResume once the view and batchOpsHelper exist again.
    private val batchResultBuffer = DetachBuffer<GalleryListViewModel.BatchResult>()

    /** Starts the collectors; called from onCreate once the ViewModel exists. */
    fun install() {
        val viewModel = scene.viewModel

        // Observe download events to refresh adapter items.
        // collectFlowWhileCreated(fragment): deliberate whole-lifetime collection —
        // handlers null-guard `adapter` (no-op while detached) and a re-attach
        // rebinds every row anyway; STARTED gating would drop rebinds for events
        // landing while backgrounded.
        collectFlowWhileCreated(scene, viewModel.downloadEvent) { event ->
            when (event) {
                is GalleryListViewModel.DownloadEvent.BulkChanged -> {
                    // Payload bind: flips only the downloaded badge, so cached
                    // holders keep their thumbnail requests running.
                    scene.adapter?.notifyItemRangeChanged(
                        0,
                        scene.adapter?.itemCount ?: 0,
                        GalleryAdapterNew.PAYLOAD_DOWNLOAD_BADGE,
                    )
                }
            }
        }

        // Server-side archive deletion: drop the row from this list. If the scene is
        // currently visible, remove inline so the user sees the fade + reflow animation.
        // If hidden (covered by the detail page), defer to onResume so the animation
        // plays after the back-stack pop instead of being burned off-screen.
        // collectFlowWhileCreated(fragment): deletions typically arrive while this
        // list is detached under the detail scene that performed the delete — a
        // view-scoped collector would lose them. The deletionBuffer + onResume
        // replay depend on whole-lifetime collection.
        collectFlowWhileCreated(scene, AppEventBus.archiveDeletedEvent) { event ->
            deletionBuffer.deliverOrBuffer(event.arcid, ready = scene.isResumed) {
                removeArchiveLocally(it)
            }
        }

        // Reader changed an archive's cover: rebind that row (new stamp → new
        // key + bust URL). Whole-lifetime like the deletion collector — the
        // reader is a separate activity, so this list is stopped meanwhile;
        // notifyItemChanged on a stopped view is applied at the next layout.
        collectFlowWhileCreated(scene, AppEventBus.archiveCoverChangedEvent) { event ->
            val helper = scene.mHelper ?: return@collectFlowWhileCreated
            val position = helper.getData().indexOfFirst { it.arcid == event.arcid }
            if (position >= 0) scene.adapter?.notifyItemChanged(position)
        }

        // Rating saved on the detail page: update the row in place. Whole-
        // lifetime like the cover collector — this list is covered then.
        collectFlowWhileCreated(scene, AppEventBus.archiveRatingChangedEvent) { event ->
            val list = scene.mHelper?.getData() ?: return@collectFlowWhileCreated
            val i = list.indexOfFirst { it.arcid == event.arcid }
            if (i >= 0) {
                list[i] = list[i].copy(rating = event.rating)
                scene.adapter?.notifyItemChanged(i)
                scene.rowHighlighter.mark(event.arcid)
            }
        }

        // Detail-page Tankoubons › Edit: same whole-lifetime collection and
        // onResume replay as deletions — the edit happens while this list is
        // covered, and the merge animation must play after the pop-back.
        collectFlowWhileCreated(scene, AppEventBus.tankMembershipChangedEvent) { event ->
            tankMembershipBuffer.deliverOrBuffer(event, ready = scene.isResumed) {
                scene.tankMerge.onTankMembershipChanged(it)
            }
        }

        // collectFlowWhileCreated(fragment): a batch runs in viewModelScope and
        // can finish while this list is detached under a detail scene the user
        // pushed mid-run. A view-scoped collector (onViewCreated +
        // viewLifecycleOwner) would lose the replay=0 result, dropping the
        // per-item failure dialog after a partial delete and skipping ClearNew's
        // badge refresh. Buffer while detached and replay in onResume, same as
        // archiveDeletedEvent above.
        //
        // The ViewModel is activity-scoped (shared by every stacked list
        // instance), so this replay=0 result is broadcast to all of them. Only
        // the scene that started the batch (owner token match) may handle or
        // buffer it; the rest ignore it, or a covered instance would replay a
        // duplicate dialog / spurious refresh on pop-back.
        collectFlowWhileCreated(scene, viewModel.batchResultEvent) { result ->
            if (result.owner !== scene.batchOwnerToken) return@collectFlowWhileCreated
            batchResultBuffer.deliverOrBuffer(result, ready = scene.isResumed && scene.batchOpsHelper != null) {
                scene.batchOpsHelper?.onBatchResult(it)
                scene.rowHighlighter.onBatchResult(it)
            }
        }
    }

    /** Replays what arrived while the list was covered; called from onResume. */
    fun drainOnResume() {
        // Drain deletions that arrived while we were hidden, deferred one frame so
        // the RecyclerView has laid out before the remove animation kicks off.
        // Only once the RecyclerView exists, else keep them buffered.
        if (scene.isListViewCreated) {
            val recyclerView = scene.recyclerView
            val drained = mutableListOf<String>()
            deletionBuffer.drain { drained.add(it) }
            if (drained.isNotEmpty()) {
                recyclerView.post { drained.forEach { removeArchiveLocally(it) } }
            }
            val membership = mutableListOf<TankMembershipChangedEvent>()
            tankMembershipBuffer.drain { membership.add(it) }
            if (membership.isNotEmpty()) {
                recyclerView.post { membership.forEach { scene.tankMerge.onTankMembershipChanged(it) } }
            }
        }

        // Replay batch results that completed while this list was detached. Only
        // once batchOpsHelper is live, else keep them buffered (never drain into
        // a null helper).
        if (scene.batchOpsHelper != null) {
            batchResultBuffer.drain {
                scene.batchOpsHelper?.onBatchResult(it)
                scene.rowHighlighter.onBatchResult(it)
            }
        }
    }

    private fun removeArchiveLocally(arcid: String) {
        // Removal left-shifts every later adapter position but the checked
        // SparseArray does not remap — an in-flight multi-select must not
        // survive it (same rationale as the getPageData refresh funnel).
        scene.multiSelectHelper?.exit()
        val helper = scene.mHelper ?: return
        for (i in 0 until helper.size()) {
            if (helper.getDataAtEx(i)?.arcid == arcid) {
                helper.removeAt(i)
                return
            }
        }
    }
}
