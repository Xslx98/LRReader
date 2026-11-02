package com.lanraragi.reader.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.VisibleForTesting
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.widget.GalleryInfoContentHelper

/**
 * The list's view-side paging state machine (ContentLayout's footer, refresh,
 * jump and diff bookkeeping). Every page it needs is asked of
 * [GalleryListViewModel.loadPage]; the outcome comes back through
 * [onGetPageData] / the framework failure path. No network, no scopes here.
 */
class GalleryListDataHelper(
    private val callback: Callback,
    private val viewModel: GalleryListViewModel,
) : GalleryInfoContentHelper() {

    interface Callback {
        fun getHostContext(): Context?

        /** The scene's query as of now (search bar, category, sort, tank mode). */
        fun currentQuery(): GalleryListQuery
        @SuppressLint("NotifyDataSetChanged")
        fun notifyAdapterDataSetChanged()
        fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int)
        fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int)
        fun notifyAdapterItemRangeChanged(positionStart: Int, itemCount: Int)
        fun notifyAdapterItemMoved(fromPosition: Int, toPosition: Int)
        fun showSearchBar()
        fun showActionFab()
        fun getString(resId: Int): String

        /** Leave multi-select mode (no-op when it is not active). */
        fun exitMultiSelect()

        /** Settle an in-flight merge-into-tankoubon animation before positions change. */
        fun cancelTankMerge() {}

        /**
         * The list now shows the results of [params] (the exact `/api/search`
         * inputs of the load that just landed). The reading context that drives
         * "continue to the next archive" must re-issue this query, not a default.
         */
        fun onSearchLoaded(params: GalleryListViewModel.SearchParams)
    }

    override fun getPageData(taskId: Int, type: Int, page: Int) {
        // Any load that replaces or prepends rows invalidates adapter positions,
        // so an in-flight multi-select must not survive it. This is the single
        // funnel every refresh trigger passes through (pull-to-refresh, FAB
        // refresh, sort change, upload-success refresh); only next-page appends
        // keep existing positions valid.
        if (type != TYPE_NEXT_PAGE && type != TYPE_NEXT_PAGE_KEEP_POS) {
            callback.exitMultiSelect()
            callback.cancelTankMerge()
        }
        viewModel.loadPage(callback.currentQuery(), page) { onLoad(taskId, it) }
    }

    /** Applies one load outcome; true when [taskId] is still the list's current task. */
    private fun onLoad(taskId: Int, load: GalleryListLoad): Boolean {
        when (load) {
            is GalleryListLoad.Page -> {
                load.issued?.let { recordLoadedSearch(taskId, it) }
                if (!isCurrentTask(taskId)) return false
                emptyString = callback.getString(load.emptyText)
                onGetPageData(taskId, load.pageCount, load.nextPage, load.rows)
            }
            is GalleryListLoad.TankCovers -> applyTankCovers(load.thumbnailUrls)
            is GalleryListLoad.Failed -> onGetFailure(load.error, taskId)
            is GalleryListLoad.FailedWith -> onGetFailure(Exception(callback.getString(load.message)), taskId)
        }
        return isCurrentTask(taskId)
    }

    /** Patches the rows of tanks whose generated cover is missing with their stand-in cover. */
    private fun applyTankCovers(thumbnailUrls: Map<String, String>) {
        val rows = data
        for (i in rows.indices) {
            val url = thumbnailUrls[rows[i].arcid]
            if (url != null && rows[i].thumbnailUrl != url) {
                rows[i] = rows[i].copy(thumbnailUrl = url)
                notifyItemRangeChanged(i, 1)
            }
        }
    }

    override fun getPageData(taskId: Int, type: Int, page: Int, append: String?) {
        getPageData(taskId, type, page)
    }

    override fun getExPageData(pageAction: Int, taskId: Int, page: Int) {
        getPageData(taskId, 0, page)
    }

    override fun getContext(): Context? {
        return callback.getHostContext()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun notifyDataSetChanged() {
        callback.notifyAdapterDataSetChanged()
    }

    override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) {
        callback.notifyAdapterItemRangeRemoved(positionStart, itemCount)
    }

    override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) {
        callback.notifyAdapterItemRangeInserted(positionStart, itemCount)
    }

    override fun notifyItemRangeChanged(positionStart: Int, itemCount: Int) {
        callback.notifyAdapterItemRangeChanged(positionStart, itemCount)
    }

    override fun notifyItemMoved(fromPosition: Int, toPosition: Int) {
        callback.notifyAdapterItemMoved(fromPosition, toPosition)
    }

    override fun onShowView(hiddenView: View, shownView: View) {
        callback.showSearchBar()
        callback.showActionFab()
    }

    override fun isDuplicate(d1: Archive, d2: Archive): Boolean {
        return d1.arcid == d2.arcid
    }

    override fun onScrollToPosition(position: Int) {
        if (0 == position) {
            callback.showSearchBar()
            callback.showActionFab()
        }
    }

    /**
     * Report the query behind a landed load, but only for the current task: a
     * superseded load must not overwrite the inputs of the list on screen.
     */
    @VisibleForTesting
    internal fun recordLoadedSearch(taskId: Int, issued: GalleryListViewModel.SearchParams) {
        if (isCurrentTask(taskId)) callback.onSearchLoaded(issued)
    }

    private fun onGetFailure(e: Exception, taskId: Int) {
        if (!isCurrentTask(taskId)) return
        // A load ends with the ViewModel, not the view, so a slow failure (up to
        // the OkHttp timeout) can land on this helper after its view is gone.
        // getContext() is null then, and the framework onGetException would
        // deref it (friendlyError / Toast.makeText) and crash on the main
        // thread. Drop the stale failure when there is no live host.
        if (getContext() == null) return
        onGetException(taskId, e)
    }
}
