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

package com.lanraragi.reader.ui.scene.download.part

import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.widget.PaginationIndicator

/**
 * Applies page changes: publishes them through [pageChangeCallback], then
 * tells the adapter how its rows changed. The user's own choices also go back
 * to the top of the list; [moveToPage] leaves the scroll position to the caller.
 */
class MyPageChangeListener(
    private var indexPage: Int,
    private var pageSize: Int,
    private val adapter: RecyclerView.Adapter<*>?,
    private val recyclerView: RecyclerView?
) : PaginationIndicator.OnChangedListener {

    var pageChangeCallback: PageChangeCallback? = null

    override fun onPageSelected(page: Int) {
        if (moveToPage(page)) recyclerView?.scrollToPosition(0)
    }

    override fun onPageSizeSelected(pageSize: Int) {
        if (this.pageSize == pageSize) return
        val oldItemCount = adapter?.itemCount ?: 0
        // Both before the adapter hears anything: it reads them for its item count.
        this.pageSize = pageSize
        pageChangeCallback?.onPageSizeChanged(pageSize)
        if (indexPage != 1) {
            indexPage = 1
            pageChangeCallback?.onPageChanged(1)
        }
        notifyAdapter(oldItemCount)
        recyclerView?.scrollToPosition(0)
    }

    /** Turns to [page]; returns false when already there. */
    fun moveToPage(page: Int): Boolean {
        if (indexPage == page) return false
        val oldItemCount = adapter?.itemCount ?: 0
        indexPage = page
        pageChangeCallback?.onPageChanged(page)
        notifyAdapter(oldItemCount)
        return true
    }

    private fun notifyAdapter(oldItemCount: Int) {
        val a = adapter ?: return
        val newItemCount = a.itemCount
        val commonCount = minOf(oldItemCount, newItemCount)
        if (commonCount > 0) {
            a.notifyItemRangeChanged(0, commonCount)
        }
        if (newItemCount > oldItemCount) {
            a.notifyItemRangeInserted(oldItemCount, newItemCount - oldItemCount)
        } else if (oldItemCount > newItemCount) {
            a.notifyItemRangeRemoved(newItemCount, oldItemCount - newItemCount)
        }
    }

    /**
     * 分页变化回调接口
     */
    interface PageChangeCallback {
        fun onPageChanged(newIndexPage: Int)
        fun onPageSizeChanged(newPageSize: Int)
    }
}
