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

package com.hippo.ehviewer.ui.scene.download.part

import android.annotation.SuppressLint
import androidx.recyclerview.widget.RecyclerView
import com.sxj.paginationlib.PaginationIndicator

/**
 * 分页指示器的页面变化监听器
 */
class MyPageChangeListener @JvmOverloads constructor(
    var indexPage: Int = 1,
    var pageSize: Int = 1,
    var isNeedInitPage: Boolean = false,
    var isDoNotScroll: Boolean = false,
    var adapter: RecyclerView.Adapter<*>? = null,
    var recyclerView: RecyclerView? = null
) : PaginationIndicator.OnChangedListener {

    var pageChangeCallback: PageChangeCallback? = null

    override fun onPageSelectedChanged(currentPagePos: Int, lastPagePos: Int, totalPageCount: Int, total: Int) {
        if (indexPage == currentPagePos) {
            isNeedInitPage = false
        }
        if (isNeedInitPage) {
            // 注意：这里需要外部传入 PaginationIndicator 实例
            // 或者通过回调方法处理
            return
        }
        if (indexPage == currentPagePos) {
            return
        }
        indexPage = currentPagePos

        // 通过回调更新主类的状态
        pageChangeCallback?.onPageChanged(indexPage)

        notifyAdapter()
    }

    override fun onPerPageCountChanged(perPageCount: Int) {
        if (pageSize == perPageCount) {
            return
        }
        pageSize = perPageCount

        // 通过回调更新主类的状态
        pageChangeCallback?.onPageSizeChanged(pageSize)

        notifyAdapter()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun notifyAdapter() {
        adapter?.notifyDataSetChanged()
        recyclerView?.let {
            if (isDoNotScroll) {
                isDoNotScroll = false
                return
            }
            it.scrollToPosition(0)
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
