package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import androidx.paging.PagingSource
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.lrr.LRRArchivePagingSource
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.widget.GalleryInfoContentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data fetching helper for GalleryListScene.
 * Manages LRR PagingSource-based data loading and result callbacks.
 * Extracted from inner class GalleryListHelper to reduce GalleryListScene's line count.
 */
class GalleryListDataHelper(private val callback: Callback) : GalleryInfoContentHelper() {

    interface Callback {
        fun getHostContext(): Context?
        fun getUrlBuilder(): ListUrlBuilder?
        fun getSortBy(): String
        fun getSortOrder(): String
        @SuppressLint("NotifyDataSetChanged")
        fun notifyAdapterDataSetChanged()
        fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int)
        fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int)
        fun showSearchBar()
        fun showActionFab()
        fun getString(resId: Int): String
    }

    override fun getPageData(taskId: Int, type: Int, page: Int) {
        val serverUrl = LRRAuthManager.getServerUrl()
        if (serverUrl.isNullOrEmpty()) return

        var filter: String? = null
        var categoryId: String? = null
        val urlBuilder = callback.getUrlBuilder()
        if (urlBuilder != null) {
            val keyword = urlBuilder.keyword
            if (!keyword.isNullOrEmpty()) {
                if (keyword.startsWith("category:")) {
                    categoryId = keyword.substring("category:".length)
                } else {
                    filter = keyword
                }
            }
        }

        val sortBy = callback.getSortBy()
        val sortOrder = callback.getSortOrder()

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
                        key = page,
                        loadSize = LRR_PAGE_SIZE,
                        placeholdersEnabled = false
                    )
                )
                when (loadResult) {
                    is PagingSource.LoadResult.Page -> {
                        val hasMore = loadResult.nextKey != null
                        withContext(Dispatchers.Main) {
                            onGetPagingSourceSuccess(loadResult.data, taskId, page, hasMore)
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

    override fun onShowView(hiddenView: View, shownView: View) {
        callback.showSearchBar()
        callback.showActionFab()
    }

    override fun isDuplicate(d1: GalleryInfoUi, d2: GalleryInfoUi): Boolean {
        return d1.gid == d2.gid
    }

    override fun onScrollToPosition(postion: Int) {
        if (0 == postion) {
            callback.showSearchBar()
            callback.showActionFab()
        }
    }

    private fun onGetPagingSourceSuccess(
        data: List<GalleryInfoUi>, taskId: Int, page: Int, hasMore: Boolean
    ) {
        if (isCurrentTask(taskId)) {
            setEmptyString(callback.getString(R.string.gallery_list_empty_hit))
            val totalPages = if (hasMore) page + 2 else page + 1
            val nextPage = if (hasMore) page + 1 else 0
            onGetPageData(taskId, totalPages, nextPage, data)
        }
    }

    private fun onGetFailure(e: Exception, taskId: Int) {
        if (isCurrentTask(taskId)) {
            onGetException(taskId, e)
        }
    }

    companion object {
        private const val TAG = "GalleryListDataHelper"
        const val LRR_PAGE_SIZE = 50
    }
}
