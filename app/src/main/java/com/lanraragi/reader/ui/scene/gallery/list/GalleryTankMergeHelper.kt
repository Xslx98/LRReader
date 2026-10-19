package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.ui.Haptics
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.R
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.event.TankMembershipChangedEvent
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.tankoubon.TankPseudoArchive
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailScene

/**
 * The gallery list's tankoubon rows: the merge-into-tankoubon choreography
 * (spec 2026-09-22), detail-page membership edits replayed on the list,
 * and rebinding tank covers after another screen changed them.
 */
class GalleryTankMergeHelper(private val scene: GalleryListScene) {

    /** [TankCoverCacheStamp] value the tank rows were last bound with. */
    private var tankCoverStampSeen = TankCoverCacheStamp.generation
    private var tankMergeAnimator: TankMergeAnimator? = null

    /**
     * A tank screen or the reader changed / revalidated tank covers while
     * this list was covered (TankCoverCacheStamp bumped): folded tank rows
     * bind their cover by that stamp, so rebind them to fetch the new one.
     */
    fun rebindTankRowsIfCoverStampMoved() {
        val stamp = TankCoverCacheStamp.generation
        if (stamp == tankCoverStampSeen) return
        tankCoverStampSeen = stamp
        val helper = scene.mHelper ?: return
        val adapter = scene.adapter ?: return
        helper.getData().forEachIndexed { index, archive ->
            if (isTankoubonId(archive.arcid)) adapter.notifyItemChanged(index)
        }
    }

    // ─── Merge-into-tankoubon choreography (spec 2026-09-22) ─────────────

    /**
     * Plays the "merge" animation for the archives that just joined
     * [tankId] and leaves the list in the state a refresh would produce
     * (group mode: members gone, tank row present — provisional if it was
     * not loaded). [onDone] fires once the list is settled, or at once when
     * there is nothing to animate, so the caller shows its feedback then.
     */
    fun runTankMerge(
        tankId: String,
        tankName: String,
        wasEmpty: Boolean,
        succeeded: List<String>,
        onDone: () -> Unit,
    ) {
        val helper = scene.mHelper
        val root = scene.rootLayout
        val hasWork = scene.isListViewCreated && succeeded.isNotEmpty()
        if (helper == null || root == null || !hasWork) {
            onDone()
            return
        }
        val recyclerView = scene.recyclerView
        cancelTankMerge()
        scene.multiSelectHelper?.exit()
        val baseUrl = LRRClientProvider.getBaseUrl()
        val groupMode = AppearanceSettings.getGroupTanks() && !TankoubonSupportGate.isUnsupported(baseUrl)
        val (first, last) = visibleRange()
        val plan = TankMergePlanner.plan(
            loadedIds = helper.getData().map { it.arcid },
            firstVisible = first,
            lastVisible = last,
            succeeded = succeeded,
            tankId = tankId,
            groupMode = groupMode,
        )
        if (plan.isNoOp) {
            onDone()
            return
        }
        val provisional = plan.provisionalInsertAt?.let {
            TankPseudoArchive.provisional(
                tankId = tankId,
                tankName = tankName,
                wasEmpty = wasEmpty,
                firstAddedThumbnailUrl = helper.getData().firstOrNull { it.arcid == succeeded.first() }?.thumbnailUrl,
                sourceProfileId = LRRAuthManager.getActiveProfileId(),
                sourceBaseUrl = baseUrl,
            )
        }
        val host = object : TankMergeAnimator.Host {
            override fun thumbViewAt(position: Int): View? =
                (scene.recyclerView.findViewHolderForAdapterPosition(position) as? GalleryAdapterNew.GalleryHolder)?.thumb

            override fun rowViewAt(position: Int): View? =
                scene.recyclerView.findViewHolderForAdapterPosition(position)?.itemView

            override fun applyEndState() {
                val insertAt = plan.provisionalInsertAt
                val replaceAt = insertAt?.takeIf { it in plan.removals && it < helper.size() }
                if (provisional != null && replaceAt != null) {
                    // The provisional row REPLACES the topmost member in place
                    // (change animation = crossfade alongside the close-up) rather
                    // than remove + insert: RecyclerView runs adds only after every
                    // removal and move has finished, and with the first visible
                    // rows gone it would also anchor the inserted row above the fold.
                    plan.removals.filter { it != replaceAt }.forEach { if (it < helper.size()) helper.removeAt(it) }
                    helper.getData()[replaceAt] = provisional
                    scene.adapter?.notifyItemChanged(replaceAt)
                    return
                }
                plan.removals.forEach { if (it < helper.size()) helper.removeAt(it) }
                if (provisional != null && insertAt != null) {
                    helper.addAt(minOf(insertAt, helper.size()), provisional)
                }
            }

            override fun batchButtonView(): View? = scene.batchOpsHelper?.tankButton
            override fun batchBarView(): View? = scene.batchOpsHelper?.bar
        }
        if (!recyclerView.isLaidOut) {
            // Nothing on screen to animate (view just re-attached): settle silently.
            host.applyEndState()
            Haptics.confirm(recyclerView)
            onDone()
            return
        }
        val animator = TankMergeAnimator(root, recyclerView, host)
        tankMergeAnimator = animator
        animator.start(plan) {
            if (tankMergeAnimator === animator) tankMergeAnimator = null
            onDone()
        }
    }

    /**
     * Detail-page membership edit (spec 2026-09-22 §6): a join plays the
     * single-flyer choreography into the first joined tank and ends in the
     * batch Snackbar (count 1, "Open"); a leave-only edit in group mode
     * reloads quietly — the archive's correct position needs the server
     * sort. Ungrouped lists have nothing to move.
     */
    fun onTankMembershipChanged(event: TankMembershipChangedEvent) {
        val baseUrl = LRRClientProvider.getBaseUrl()
        val groupMode = AppearanceSettings.getGroupTanks() && !TankoubonSupportGate.isUnsupported(baseUrl)
        if (!groupMode) return
        val joined = event.joined.firstOrNull()
        if (joined == null) {
            if (event.left.isNotEmpty()) scene.mHelper?.firstRefresh()
            return
        }
        // Only a row of THIS list can merge; an archive edited from elsewhere
        // (history, another server's list) has nothing here to animate.
        val loadedHere = scene.mHelper?.getData()?.any { it.arcid == event.arcid } == true
        if (!loadedHere) return
        runTankMerge(joined.id, joined.name, joined.wasEmpty, listOf(event.arcid)) {
            val res = scene.resources2 ?: return@runTankMerge
            scene.showTip(
                res.getQuantityString(R.plurals.batch_done_tankoubon, 1, 1),
                BaseScene.LENGTH_LONG,
                res.getString(R.string.tank_merge_open),
            ) { openTankoubonDetail(joined.id, joined.name) }
        }
    }

    /** Settles an in-flight merge (end state applied, feedback delivered). */
    fun cancelTankMerge() {
        val animator = tankMergeAnimator ?: return
        tankMergeAnimator = null
        animator.cancel()
    }

    private fun visibleRange(): Pair<Int, Int> {
        val lm = scene.recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return -1 to -1
        val first = lm.findFirstVisibleItemPositions(null).filter { it >= 0 }.minOrNull() ?: -1
        val last = lm.findLastVisibleItemPositions(null).filter { it >= 0 }.maxOrNull() ?: -1
        return first to last
    }

    /** Post-merge Snackbar "Open" → the tank DETAIL page (spec 2026-09-22 §3). */
    fun openTankoubonDetail(tankId: String, tankName: String) {
        val args = Bundle().apply {
            putString(TankDetailScene.KEY_TANK_ID, tankId)
            putString(TankDetailScene.KEY_TANK_NAME, tankName)
            putLong(TankDetailScene.KEY_PROFILE_ID, LRRAuthManager.getActiveProfileId())
        }
        scene.startScene(
            Announcer(TankDetailScene::class.java).setArgs(args)
                .setRequestCode(scene, GalleryItemActionHelper.REQUEST_CODE_GALLERY_DETAIL)
        )
    }
}
