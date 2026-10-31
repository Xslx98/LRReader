package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.ui.RowHighlight

/**
 * Which home-list rows get the [RowHighlight] cue: rows a batch download or
 * "add to category" just changed, a row whose rating changed on the detail
 * page, and the row the user just came back from. Rows changed while the
 * list is covered wait for [flushAfterReturn]; rows off screen are skipped.
 */
class GalleryRowHighlighter(private val scene: GalleryListScene) {

    private val pending = LinkedHashSet<String>()

    fun mark(arcid: String) {
        pending += arcid
    }

    fun onBatchResult(result: GalleryListViewModel.BatchResult) {
        val op = result.op
        if (op is GalleryListViewModel.BatchOp.Download || op is GalleryListViewModel.BatchOp.AddToCategory) {
            result.succeeded.forEach(::mark)
            flush(0L)
        }
    }

    /** After the scene close animation, so the cue is seen. */
    fun flushAfterReturn() = flush(RETURN_DELAY_MS)

    private fun flush(delayMs: Long) {
        if (pending.isEmpty() || !scene.isListViewCreated) return
        scene.recyclerView.postDelayed(::flushNow, delayMs)
    }

    private fun flushNow() {
        if (!scene.isResumed || !scene.isListViewCreated) return
        val data = scene.mHelper?.data ?: return
        val recyclerView = scene.recyclerView
        val arcids = pending.toList()
        pending.clear()
        for (arcid in arcids) {
            val position = data.indexOfFirst { it.arcid == arcid }
            if (position < 0) continue
            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let(RowHighlight::flash)
        }
    }

    private companion object {
        /** scene_close_* run 250 ms. */
        const val RETURN_DELAY_MS = 250L
    }
}
