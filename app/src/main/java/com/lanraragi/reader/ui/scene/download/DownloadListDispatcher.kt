package com.lanraragi.reader.ui.scene.download

import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.download.ProgressSnapshot

/**
 * Turns the Downloads page's two flows into adapter notifications: the
 * structural list through [DownloadListDiff], the live progress map as
 * targeted `PAYLOAD_PROGRESS` rebinds (ADR-001 Option D).
 */
class DownloadListDispatcher(
    private val viewModel: DownloadsViewModel,
    private val adapter: () -> RecyclerView.Adapter<*>?,
    private val list: () -> List<DownloadInfo>?,
) {

    private var lastSnapshot: List<DownloadInfo> = emptyList()

    /**
     * Most recent progress map observed from [DownloadsViewModel.progressMap].
     * Used to compute a per-arcid delta and dispatch only the changed rows
     * via `notifyItemChanged(pos, PAYLOAD_PROGRESS)`.
     */
    private var lastProgressMap: Map<String, ProgressSnapshot> = emptyMap()

    /** Dispatches the change from the last snapshot to [newList] to the adapter (see [DownloadListDiff]). */
    fun dispatchDiffUpdate(newList: MutableList<DownloadInfo>) {
        val old = lastSnapshot
        lastSnapshot = newList
        adapter()?.let { DownloadListDiff.dispatch(old, newList, AdapterListUpdateCallback(it)) }
    }

    /**
     * Dispatch targeted progress updates for any arcid whose [ProgressSnapshot]
     * changed since the last emission. Uses a payload so [DownloadAdapter]
     * can rebind only the progress / speed / percent views — no full item
     * rebind, no image re-fetch.
     */
    fun dispatchProgressChanges(newMap: Map<String, ProgressSnapshot>) {
        val oldMap = lastProgressMap
        lastProgressMap = newMap
        val list = list() ?: return
        val adapter = adapter() ?: return
        // Collect arcids whose snapshot meaningfully differs (or was
        // added/removed). ProgressSnapshot is a data class so `!=` compares
        // by value — same arcid, same fields → skip.
        val affected = HashSet<String>()
        for ((arcid, snap) in newMap) {
            if (oldMap[arcid] != snap) affected.add(arcid)
        }
        for (arcid in oldMap.keys) {
            if (arcid !in newMap) affected.add(arcid)
        }
        if (affected.isEmpty()) return
        // A member's tick must repaint the CARD that folds it (the member
        // row itself is not on the display list).
        for (arcid in affected.toList()) {
            viewModel.tankCardIdFor(arcid)?.let { affected.add(it) }
        }
        list.forEachIndexed { indexInList, info ->
            val id = info.arcid
            // Null = not on the page currently shown; notifying the modulo
            // position would rebind the wrong visible row.
            val inPagePos = if (id != null && id in affected) {
                viewModel.adapterPositionForListIndex(indexInList)
            } else {
                null
            }
            if (inPagePos != null) adapter.notifyItemChanged(inPagePos, DownloadsScene.PAYLOAD_PROGRESS)
        }
    }
}
