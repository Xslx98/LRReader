/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.scene.download

import android.view.View
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener
import com.sxj.paginationlib.PaginationIndicator

/**
 * Manages the pagination indicator and page navigation for the downloads list.
 *
 * Extracted from [DownloadsScene] to reduce its line count. Operates on
 * the [PaginationIndicator] view and synchronizes state with the ViewModel
 * via the [MyPageChangeListener] callback.
 */
internal class DownloadPaginationHelper(
    private val viewModel: DownloadsViewModel
) {

    /** Whether the next pagination init should restore the current page. */
    var needInitPage = false

    /** Whether the next pagination init should restore the page size. */
    var needInitPageSize = false

    /** Whether to suppress scroll-to-top on next page change. */
    var doNotScroll = false

    /**
     * Updates the pagination indicator visibility and state based on the
     * current download list size and pagination settings.
     *
     * @param indicator the [PaginationIndicator] view
     * @param list the current download list (may be null)
     * @param pageChangeListener the listener to attach to the indicator
     */
    fun updatePaginationIndicator(
        indicator: PaginationIndicator?,
        list: List<*>?,
        pageChangeListener: MyPageChangeListener?
    ) {
        val ind = indicator ?: return
        val lst = list ?: return
        val paginationSize = viewModel.paginationSize
        val canPagination = viewModel.canPagination
        val pageSize = viewModel.pageSize.value
        val indexPage = viewModel.indexPage.value
        if (lst.size < paginationSize || !canPagination) {
            ind.visibility = View.GONE
            return
        }
        ind.visibility = View.VISIBLE
        needInitPageSize = true
        ind.initPaginationIndicator(pageSize, viewModel.perPageCountChoices, lst.size, indexPage)
        ind.setListener(pageChangeListener)

        // Synchronize page listener state
        pageChangeListener?.let {
            it.indexPage = indexPage
            it.pageSize = pageSize
            it.isNeedInitPage = needInitPage
            it.isDoNotScroll = doNotScroll
        }
    }

    /**
     * Jumps pagination to the page containing [position], scrolls
     * the RecyclerView to the item, and updates the indicator.
     *
     * @param position absolute position in the full download list
     * @param list the current download list
     * @param recyclerView the RecyclerView to scroll
     * @param indicator the pagination indicator to update
     */
    fun initPage(
        position: Int,
        list: List<*>?,
        recyclerView: androidx.recyclerview.widget.RecyclerView?,
        indicator: PaginationIndicator?
    ) {
        if (list != null && list.size > viewModel.paginationSize && viewModel.canPagination) {
            viewModel.setIndexPage(position / viewModel.pageSize.value + 1)
        }
        doNotScroll = true
        indicator?.skip2Pos(viewModel.indexPage.value)
        recyclerView?.scrollToPosition(viewModel.listIndexInPage(position))
    }

    /**
     * Returns the spinner position for the given [pageSize] within
     * the per-page-count choices array.
     */
    fun getPageSizePos(pageSize: Int): Int {
        var index = 0
        for (i in viewModel.perPageCountChoices.indices) {
            if (pageSize == viewModel.perPageCountChoices[i]) {
                index = i
                break
            }
        }
        return index
    }
}
