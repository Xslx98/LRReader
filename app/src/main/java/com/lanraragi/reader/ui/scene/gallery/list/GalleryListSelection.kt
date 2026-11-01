package com.lanraragi.reader.ui.scene.gallery.list

import android.app.Activity
import android.view.View
import com.lanraragi.reader.widget.list.EasyRecyclerView
import com.lanraragi.reader.util.ViewUtils
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.ListMultiSelectHelper

/**
 * Multi-select on the gallery list: the thumb tap that toggles a row, the
 * batch action bar and the multi-select / drag-select helper. Tank rows are
 * never checked — except in tank mode, where they are the only rows.
 */
object GalleryListSelection {

    /** A row can be checked: tank rows in tank mode, archive rows otherwise. */
    fun isSelectable(scene: GalleryListScene, archive: Archive): Boolean =
        isTankoubonId(archive.arcid) == scene.isTankMode

    /** Wires selection for the list view just created in onCreateView2. */
    fun bind(scene: GalleryListScene, adapter: GalleryAdapterNew, mainLayout: View) {
        adapter.setThumbItemClickListener(object : GalleryAdapterNew.OnThumbItemClickListener {
            override fun onThumbItemClick(position: Int, view: View, archive: Archive?) {
                // The thumb owns its own click listener (bypasses the
                // EasyRecyclerView item-click path) — in multi-select mode it
                // must toggle the row instead of opening the tag-chip popup.
                // Tank rows stay untoggleable here too.
                if (scene.multiSelectHelper?.isActive == true &&
                    archive != null && !isSelectable(scene, archive)
                ) {
                    return
                }
                if (scene.multiSelectHelper?.toggleChecked(position) == true) return
                scene.tagChipHelper?.onThumbItemClick(position, view, archive)
            }
        })
        val recyclerView = scene.recyclerView
        val batchBar = ViewUtils.requireView(mainLayout, R.id.batch_action_bar)
        scene.batchOpsHelper = GalleryBatchOpsHelper(batchBar, object : GalleryBatchOpsHelper.Callback {
            override val activity: Activity? get() = scene.activity2
            override val viewModel: GalleryListViewModel get() = scene.viewModel
            override val downloadManager: DownloadManager
                get() = scene.downloadManager

            override fun selectedArchives(): List<Archive> =
                scene.multiSelectHelper?.checkedPositions()
                    ?.mapNotNull { scene.mHelper?.getDataAtEx(it) }
                    .orEmpty()
                    // select-all can still visually check tank rows; they must
                    // never reach a batch op
                    .filter { isSelectable(scene, it) }

            override fun activeProfileId(): Long = LRRAuthManager.getActiveProfileId()
            override fun isSelectionActive(): Boolean = scene.multiSelectHelper?.isActive == true
            override fun exitSelection() { scene.multiSelectHelper?.exit() }
            override fun checkAllSelection() { scene.multiSelectHelper?.checkAll() }
            override fun refreshList() { scene.mHelper?.refresh() }
            override fun showTip(message: String) {
                scene.showTip(message, BaseScene.LENGTH_SHORT)
            }
            override fun showTipWithAction(message: String, actionText: String, action: () -> Unit) {
                scene.showTip(message, BaseScene.LENGTH_LONG, actionText, action)
            }
            override fun animateTankMerge(
                op: GalleryListViewModel.BatchOp.AddToTankoubon,
                succeeded: List<String>,
                onDone: () -> Unit,
            ) {
                scene.tankMerge.runTankMerge(op.tankId, op.tankName, op.wasEmpty, succeeded, onDone)
            }
            override fun openTankoubon(tankId: String, tankName: String) {
                scene.tankMerge.openTankoubonDetail(tankId, tankName)
            }
            override fun batchOwnerToken(): Any = scene.batchOwnerToken
            override fun isTankMode(): Boolean = scene.isTankMode
        })
        val multiSelect = ListMultiSelectHelper(
            recyclerView = { if (scene.isListViewCreated) scene.recyclerView else null },
            longClickListener = { scene },
            onModeChanged = { active -> scene.batchOpsHelper?.onModeChanged(active) },
            onCheckedChanged = { count -> scene.batchOpsHelper?.onCheckedChanged(count) },
        )
        scene.multiSelectHelper = multiSelect
        // intoCustomChoiceMode() is a no-op unless the custom choice mode is
        // configured, and it dispatches to the listener without a null check —
        // both calls below are required before the mode can be entered.
        recyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        recyclerView.setCustomCheckedListener(multiSelect.choiceListener)
        // Unselectable rows are passed over by a drag, never checked.
        multiSelect.attachDragSelect(recyclerView) { position ->
            scene.mHelper?.getDataAtEx(position)?.let { isSelectable(scene, it) } ?: false
        }
    }
}
