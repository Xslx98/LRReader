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
package com.lanraragi.reader.ui.scene.download

import android.view.View
import com.lanraragi.reader.ui.scene.download.part.MyPageChangeListener
import com.lanraragi.reader.widget.PageWindow
import com.lanraragi.reader.widget.PaginationIndicator

/**
 * Manages the pagination indicator and page navigation for the downloads list.
 *
 * Extracted from [DownloadsScene] to reduce its line count. The ViewModel owns
 * the page state, [MyPageChangeListener] is its only writer, and the indicator
 * only shows what this helper binds.
 */
internal class DownloadPaginationHelper(
    private val viewModel: DownloadsViewModel
) {

    /**
     * Updates the pagination indicator visibility and state based on the
     * current download list size and pagination settings. A list that shrank
     * under the current page moves the page back to its new end.
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
        if (lst.size < viewModel.paginationSize || !viewModel.canPagination) {
            ind.visibility = View.GONE
            return
        }
        ind.visibility = View.VISIBLE
        val pageSize = viewModel.pageSize.value
        val pageCount = PageWindow.pageCount(lst.size, pageSize)
        pageChangeListener?.moveToPage(PageWindow.clampPage(viewModel.indexPage.value, pageCount))
        ind.listener = pageChangeListener
        ind.bind(lst.size, pageSize, viewModel.indexPage.value, viewModel.perPageCountChoices)
    }

    /**
     * Jumps pagination to the page containing [position], scrolls
     * the RecyclerView to the item, and updates the indicator.
     *
     * @param position absolute position in the full download list
     * @param list the current download list
     * @param recyclerView the RecyclerView to scroll
     * @param indicator the pagination indicator to update
     * @param pageChangeListener turns the page
     */
    fun initPage(
        position: Int,
        list: List<*>?,
        recyclerView: androidx.recyclerview.widget.RecyclerView?,
        indicator: PaginationIndicator?,
        pageChangeListener: MyPageChangeListener?
    ) {
        if (list != null && list.size > viewModel.paginationSize && viewModel.canPagination) {
            pageChangeListener?.moveToPage(position / viewModel.pageSize.value + 1)
        }
        updatePaginationIndicator(indicator, list, pageChangeListener)
        recyclerView?.scrollToPosition(viewModel.listIndexInPage(position))
    }
}
