package com.lanraragi.reader.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import androidx.paging.PagingSource
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.client.api.LRRArchivePagingSource
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.widget.GalleryInfoContentHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data fetching helper for GalleryListScene.
 * Manages LRR PagingSource-based data loading and result callbacks.
 * Extracted from inner class GalleryListHelper to reduce GalleryListScene's line count.
 */
class GalleryListDataHelper(private val callback: Callback) : GalleryInfoContentHelper() {

    /**
     * Dense-page-index → raw-offset mapping. The server decides how many rows
     * a request returns (archives_per_page), so the next page's start offset
     * must be chained from the previous load instead of computed as
     * page * LRR_PAGE_SIZE (audit NET-2). Main-thread only.
     */
    private val pageOffsets = PageOffsetTracker(LRR_PAGE_SIZE)

    interface Callback {
        fun getHostContext(): Context?
        fun getUrlBuilder(): ListUrlBuilder?
        fun getSortBy(): String
        fun getSortOrder(): String
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
        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl.isNullOrEmpty()) {
            // Signal failure instead of returning silently, otherwise the refresh
            // spinner spins forever when no server profile is configured.
            onGetFailure(Exception(callback.getString(R.string.lrr_no_servers)), taskId)
            return
        }

        val (filter, categoryId) = searchInputsFor(callback.getUrlBuilder())

        val sortBy = callback.getSortBy()
        val sortOrder = callback.getSortOrder()

        // Every refresh restarts at page 0; a stale chain from the previous
        // result set must not leak into the new one.
        if (page == 0) {
            pageOffsets.reset()
        }
        val startOffset = pageOffsets.offsetFor(page)

        val pagingSource = LRRArchivePagingSource(
            client = LRRClientProvider.getClient(),
            baseUrl = LRRClientProvider.getBaseUrl(),
            filter = filter,
            category = categoryId,
            sortby = sortBy,
            order = sortOrder
        )

        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val loadResult = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        // ContentLayout counts dense page indexes (0,1,2,…) but
                        // the source keys are raw item offsets (NET-2).
                        key = startOffset,
                        loadSize = LRR_PAGE_SIZE,
                        placeholdersEnabled = false
                    )
                )
                when (loadResult) {
                    is PagingSource.LoadResult.Page -> {
                        withContext(Dispatchers.Main) {
                            onGetPagingSourceSuccess(
                                loadResult.data, taskId, page, loadResult.nextKey
                            )
                        }
                    }
                    is PagingSource.LoadResult.Error -> {
                        withContext(Dispatchers.Main) {
                            onGetFailure(
                                loadResult.throwable as? Exception
                                    ?: Exception(loadResult.throwable),
                                taskId
                            )
                        }
                    }
                    is PagingSource.LoadResult.Invalid -> {
                        withContext(Dispatchers.Main) {
                            onGetFailure(Exception("PagingSource invalidated"), taskId)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "LRR paging search failed", e)
                withContext(Dispatchers.Main) {
                    onGetFailure(e, taskId)
                }
            }
        }
    }

    override fun getPageData(taskId: Int, type: Int, page: Int, append: String) {
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

    override fun onScrollToPosition(postion: Int) {
        if (0 == postion) {
            callback.showSearchBar()
            callback.showActionFab()
        }
    }

    private fun onGetPagingSourceSuccess(
        data: List<Archive>, taskId: Int, page: Int, nextOffset: Int?
    ) {
        if (isCurrentTask(taskId)) {
            pageOffsets.recordLoaded(page, nextOffset)
            setEmptyString(callback.getString(R.string.gallery_list_empty_hit))
            val hasMore = nextOffset != null
            val totalPages = if (hasMore) page + 2 else page + 1
            val nextPage = if (hasMore) page + 1 else 0
            onGetPageData(taskId, totalPages, nextPage, data)
        }
    }

    private fun onGetFailure(e: Exception, taskId: Int) {
        if (!isCurrentTask(taskId)) return
        // The load runs on the process-level ioScope and outlives this Scene, so a
        // slow failure (up to the OkHttp timeout) can arrive after the Scene has
        // been popped. By then getContext() is null, and the framework
        // onGetException would deref it (friendlyError / Toast.makeText) and crash
        // on the main thread. Drop the stale failure when there is no live host.
        if (getContext() == null) return
        onGetException(taskId, e)
    }

    /** The two `/api/search` inputs a [ListUrlBuilder] contributes. */
    data class SearchInputs(val filter: String?, val categoryId: String?)

    companion object {
        private const val TAG = "GalleryListDataHelper"
        const val LRR_PAGE_SIZE = 50

        /**
         * Builder → search parameters. The keyword is plain `filter` text and
         * [ListUrlBuilder.categoryId] is the `category` parameter; both may be
         * present (search within a category). The retired "category:<id>"
         * keyword protocol is deliberately NOT recognised here — MIGRATION_30_31
         * rewrote every persisted row, so such a keyword is ordinary text.
         */
        @JvmStatic
        fun searchInputsFor(urlBuilder: ListUrlBuilder?): SearchInputs = SearchInputs(
            filter = urlBuilder?.keyword?.trim()?.takeIf { it.isNotEmpty() },
            categoryId = urlBuilder?.categoryId?.takeIf { it.isNotBlank() }
        )
    }
}
