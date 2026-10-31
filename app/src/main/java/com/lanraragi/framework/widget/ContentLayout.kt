/*
 * Copyright 2015 Hippo Seven
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

package com.lanraragi.framework.widget

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.util.IntIdGenerator
import com.lanraragi.reader.util.IntList
import com.lanraragi.reader.util.ViewUtils
import com.lanraragi.reader.util.attrColor
import com.lanraragi.reader.widget.ListRefreshLayout
import com.lanraragi.reader.widget.ViewTransition
import com.lanraragi.reader.widget.list.EasyRecyclerView
import com.lanraragi.reader.widget.list.FastScroller
import com.lanraragi.reader.widget.list.HandlerDrawable
import com.lanraragi.reader.widget.list.LayoutManagerUtils
import kotlin.math.max
import kotlin.math.min

class ContentLayout : FrameLayout {

    private lateinit var progressView: View
    private lateinit var tipView: TextView
    private lateinit var contentView: ViewGroup

    lateinit var refreshLayout: ListRefreshLayout
        private set
    lateinit var recyclerView: EasyRecyclerView
        private set
    lateinit var fastScroller: FastScroller
        private set

    private lateinit var contentHelper: ContentHelper<*>

    private var recyclerViewOriginTop = 0
    private var recyclerViewOriginBottom = 0

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        LayoutInflater.from(context).inflate(R.layout.widget_content_layout, this)

        progressView = findViewById(R.id.progress)
        tipView = findViewById(R.id.tip)
        contentView = findViewById(R.id.content_view)

        refreshLayout = contentView.findViewById(R.id.refresh_layout)
        fastScroller = contentView.findViewById(R.id.fast_scroller)
        recyclerView = refreshLayout.findViewById(R.id.recycler_view)

        fastScroller.attachToRecyclerView(recyclerView)
        val drawable = HandlerDrawable()
        drawable.setColor(context.attrColor(R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(drawable)

        refreshLayout.setColorSchemeResources(
            R.color.loading_indicator_red,
            R.color.loading_indicator_purple,
            R.color.loading_indicator_blue,
            R.color.loading_indicator_cyan,
            R.color.loading_indicator_green,
            R.color.loading_indicator_yellow
        )

        recyclerViewOriginTop = recyclerView.paddingTop
        recyclerViewOriginBottom = recyclerView.paddingBottom
    }

    /**
     * Shows [loadingView] instead of the centred spinner while the
     * content loads (e.g. a skeleton of the list). Call before
     * [setHelper].
     */
    fun setLoadingView(loadingView: View) {
        val index = indexOfChild(progressView)
        removeView(progressView)
        loadingView.id = R.id.progress
        loadingView.visibility = progressView.visibility
        addView(loadingView, index, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progressView = loadingView
    }

    fun setHelper(helper: ContentHelper<*>?) {
        contentHelper = requireNotNull(helper) { "ContentLayout needs a ContentHelper" }
        helper.init(this)
    }

    fun showFastScroll() {
        if (!fastScroller.isAttached) {
            fastScroller.attachToRecyclerView(recyclerView)
        }
    }

    fun hideFastScroll() {
        fastScroller.detachedFromRecyclerView()
    }

    fun setFitPaddingTop(fitPaddingTop: Int) {
        // RecyclerView
        recyclerView.setPadding(
            recyclerView.paddingLeft, recyclerViewOriginTop + fitPaddingTop, recyclerView.paddingRight, recyclerView.paddingBottom
        )
        // RefreshLayout
        refreshLayout.setProgressViewOffset(false, fitPaddingTop, fitPaddingTop + ViewUtils.dp2pix(context, 32f))
    }

    fun setFitPaddingBottom(fitPaddingBottom: Int) {
        // RecyclerView
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop, recyclerView.paddingRight,
            recyclerViewOriginBottom + fitPaddingBottom
        )
    }

    override fun onSaveInstanceState(): Parcelable {
        return contentHelper.saveInstanceState(super.onSaveInstanceState())
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        super.onRestoreInstanceState(contentHelper.restoreInstanceState(state))
    }

    abstract class ContentHelper<E : Parcelable> : ViewTransition.OnShowViewListener {

        private lateinit var progressView: View
        private lateinit var tipView: TextView
        private lateinit var contentView: ViewGroup

        private lateinit var refreshLayout: ListRefreshLayout
        private lateinit var recyclerView: EasyRecyclerView

        private lateinit var viewTransition: ViewTransition

        /** The loaded rows. */
        private var rows = ArrayList<E>()

        /** The loaded rows, live: callers may replace a row in place. */
        val data: MutableList<E>
            get() = rows

        /**
         * Generates task ids
         */
        private val idGenerator = IntIdGenerator()

        /**
         * The page divider indexes
         *
         * For example, the data contain page 3, page 4, page 5,
         * page 3 size is 7, page 4 size is 8, page 5 size is 9,
         * so `pageDivider` contain 7, 15, 24.
         */
        private var pageDivider = IntList()

        /**
         * The first page in [rows]
         */
        private var startPage = 0

        /**
         * The last page + 1 in [rows]
         */
        private var endPage = 0

        /**
         * The available page count.
         */
        var pages = 0
            private set

        /** The next page the last loaded page reported (-1: unknown). */
        private var reportedNextPage = 0

        var resultCount: String? = null
        var firstHref: String? = null
        var prevHref: String? = null
        var nextHref: String? = null
        var lastHref: String? = null

        private var currentTaskId = 0
        private var currentTaskType = 0
        private var currentTaskPage = 0

        private var nextPageScrollSize = 0

        /** The tip shown for an empty result. */
        var emptyString: String? = "No hint"

        private val onScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!refreshLayout.isAnyRefreshing && refreshLayout.isAlmostBottom() && hasMorePages()) {
                    // Get next page
                    refreshLayout.setFooterRefreshing(true)
                    onRefreshListener.onFooterRefresh()
                }
            }
        }

        /** Another page may follow the loaded ones (or the page count is unknown). */
        private fun hasMorePages(): Boolean = endPage < pages || reportedNextPage == -1 || pages == -1

        private val onRefreshListener = object : ListRefreshLayout.OnRefreshListener {
            override fun onHeaderRefresh() {
                if (startPage > 0) {
                    currentTaskId = idGenerator.nextId()
                    currentTaskType = TYPE_PRE_PAGE_KEEP_POS
                    currentTaskPage = startPage - 1
                    getPageData(currentTaskId, currentTaskType, currentTaskPage)
                } else if (!prevHref.isNullOrEmpty()) {
                    currentTaskId = idGenerator.nextId()
                    currentTaskType = TYPE_PRE_PAGE_KEEP_POS
                    getExPageData(GOTO_PREV_PAGE, currentTaskId, currentTaskPage)
                } else {
                    doRefresh()
                }
            }

            override fun onFooterRefresh() {
                if (endPage == -1 || pages == -1) {
                    currentTaskId = idGenerator.nextId()
                    currentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                    getExPageData(GOTO_NEXT_PAGE, currentTaskId, pages)
                } else if (endPage < pages) {
                    // Get next page
                    // Fill pages before NextPage with empty list
                    while (reportedNextPage > endPage && endPage < pages) {
                        currentTaskId = idGenerator.nextId()
                        currentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                        currentTaskPage = endPage
                        onGetPageData(currentTaskId, pages, reportedNextPage, emptyList())
                    }
                    currentTaskId = idGenerator.nextId()
                    currentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                    currentTaskPage = endPage
                    getPageData(currentTaskId, currentTaskType, currentTaskPage)
                } else if (endPage == pages) {
                    // Refresh last page
                    currentTaskId = idGenerator.nextId()
                    currentTaskType = TYPE_REFRESH_PAGE
                    currentTaskPage = endPage - 1
                    getPageData(currentTaskId, currentTaskType, currentTaskPage)
                } else {
                    Log.e(TAG, "Try to footer refresh, but endPage = $endPage, pages = $pages")
                    refreshLayout.setFooterRefreshing(false)
                }
            }
        }

        private val onScrollToPositionListener =
            LayoutManagerUtils.OnScrollToPositionListener { position -> onScrollToPosition(position) }

        // Called by ContentLayout.setHelper; the stable JVM name keeps the baseline-profile rule valid.
        @JvmName("init")
        internal fun init(contentLayout: ContentLayout) {
            nextPageScrollSize = ViewUtils.dp2pix(contentLayout.context, 48f)

            progressView = contentLayout.progressView
            tipView = contentLayout.tipView
            contentView = contentLayout.contentView

            refreshLayout = contentLayout.refreshLayout
            recyclerView = contentLayout.recyclerView

            val drawable = checkNotNull(ContextCompat.getDrawable(requireContext(), R.drawable.big_sad_pandroid))
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            tipView.setCompoundDrawables(null, drawable, null, null)

            viewTransition = ViewTransition(contentView, progressView, tipView)
            viewTransition.onShowViewListener = this

            recyclerView.addOnScrollListener(onScrollListener)
            refreshLayout.setOnRefreshListener(onRefreshListener)

            // Tap the tip to reload
            tipView.setOnClickListener { refresh() }
        }

        /**
         * Call [onGetPageData] when get data
         *
         * @param taskId task id
         * @param page   the page to get
         */
        protected abstract fun getPageData(taskId: Int, type: Int, page: Int)

        protected abstract fun getPageData(taskId: Int, type: Int, page: Int, append: String?)

        protected abstract fun getExPageData(pageAction: Int, taskId: Int, page: Int)

        protected abstract fun getContext(): Context?

        /** [getContext] of an attached helper, whose host is alive. */
        private fun requireContext(): Context = checkNotNull(getContext()) { "ContentHelper has no host context" }

        protected abstract fun notifyDataSetChanged()

        protected abstract fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int)

        protected abstract fun notifyItemRangeInserted(positionStart: Int, itemCount: Int)

        /**
         * Granular change notification used by the diff dispatch. Default
         * falls back to a full refresh so existing subclasses keep their
         * behaviour; adapters that support it should override and forward
         * to RecyclerView's notifyItemRangeChanged.
         */
        protected open fun notifyItemRangeChanged(positionStart: Int, itemCount: Int) {
            notifyDataSetChanged()
        }

        /** See [notifyItemRangeChanged]. */
        protected open fun notifyItemMoved(fromPosition: Int, toPosition: Int) {
            notifyDataSetChanged()
        }

        protected open fun onScrollToPosition(position: Int) {
        }

        override fun onShowView(hiddenView: View, shownView: View) {
            // Optional hook for subclasses.
        }

        val shownViewIndex: Int
            get() = viewTransition.shownViewIndex

        fun setRefreshLayoutEnable(enable: Boolean) {
            refreshLayout.isEnabled = enable
        }

        fun setEnable(enable: Boolean) {
            refreshLayout.isEnabled = enable
        }

        /**
         * @throws IndexOutOfBoundsException if `location < 0 || location >= size()`
         */
        fun getDataAt(location: Int): E {
            return rows[location]
        }

        fun getDataAtEx(location: Int): E? {
            return if (location >= 0 && location < rows.size) {
                rows[location]
            } else {
                null
            }
        }

        fun size(): Int {
            return rows.size
        }

        fun isCurrentTask(taskId: Int): Boolean {
            return currentTaskId == taskId
        }

        fun addAt(index: Int, data: E) {
            rows.add(index, data)
            onAddData(data)

            for (i in 0 until pageDivider.size()) {
                val divider = pageDivider.get(i)
                if (index < divider) {
                    pageDivider.set(i, divider + 1)
                }
            }

            notifyItemRangeInserted(index, 1)
        }

        fun removeAt(index: Int) {
            val data = rows.removeAt(index)
            onRemoveData(data)

            for (i in 0 until pageDivider.size()) {
                val divider = pageDivider.get(i)
                if (index < divider) {
                    pageDivider.set(i, divider - 1)
                }
            }

            notifyItemRangeRemoved(index, 1)
        }

        protected abstract fun isDuplicate(d1: E, d2: E): Boolean

        /**
         * Compute the difference between `oldData` and the current [rows],
         * then dispatch granular insert/remove/move/change notifications.
         *
         * Uses [DiffUtil] (see
         * [official docs](https://developer.android.com/reference/androidx/recyclerview/widget/DiffUtil))
         * to avoid the full-invalidation cost of `notifyDataSetChanged()`.
         *
         * [DiffUtil.calculateDiff] runs on the calling thread (main)
         * over the ENTIRE accumulated list, so its cost grows with scroll
         * depth: past [MAX_FULL_DIFF] rows the old rows are dropped
         * and the new ones inserted as two range notifications instead.
         */
        private fun dispatchDiffUpdates(oldData: List<E>) {
            if (oldData.size > MAX_FULL_DIFF || rows.size > MAX_FULL_DIFF) {
                if (oldData.isNotEmpty()) notifyItemRangeRemoved(0, oldData.size)
                if (rows.isNotEmpty()) notifyItemRangeInserted(0, rows.size)
                return
            }
            dispatchWindowDiff(oldData, rows, 0)
        }

        /**
         * Diff [oldData] against [newData], which occupy the same window of
         * the adapter starting at [offset]; rows outside it are unchanged.
         * Runs on the calling thread (main), so callers keep windows small.
         */
        private fun dispatchWindowDiff(oldData: List<E>, newData: List<E>, offset: Int) {
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return oldData.size
                }

                override fun getNewListSize(): Int {
                    return newData.size
                }

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return isDuplicate(oldData[oldItemPosition], newData[newItemPosition])
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    // isDuplicate checks identity (same GID); content equality
                    // would require a full field comparison. Since the server may
                    // update metadata between requests, assume content changed
                    // when the item is present in both lists — the rebind cost
                    // is negligible compared to full-layout invalidation.
                    return oldData[oldItemPosition] == newData[newItemPosition]
                }
            })
            result.dispatchUpdatesTo(object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) {
                    notifyItemRangeInserted(offset + position, count)
                }

                override fun onRemoved(position: Int, count: Int) {
                    notifyItemRangeRemoved(offset + position, count)
                }

                override fun onMoved(fromPosition: Int, toPosition: Int) {
                    // Granular move — a full notifyDataSetChanged here used
                    // to defeat the diff that was just computed (every
                    // refresh with any content delta rebound all rows).
                    notifyItemMoved(offset + fromPosition, offset + toPosition)
                }

                override fun onChanged(position: Int, count: Int, payload: Any?) {
                    notifyItemRangeChanged(offset + position, count)
                }
            })
        }

        /**
         * [data] without the rows that duplicate one of `rows[start, end)`.
         * Returns a copy: Kotlin callers may hand in read-only lists.
         */
        private fun removeDuplicateData(data: List<E>, start: Int, end: Int): List<E> {
            val from = max(0, start)
            val to = min(rows.size, end)
            return data.filterNot { d ->
                var duplicate = false
                for (i in from until to) {
                    if (isDuplicate(d, rows[i])) {
                        duplicate = true
                        break
                    }
                }
                duplicate
            }
        }

        protected open fun onAddData(data: E) {
        }

        protected open fun onAddData(data: List<E>) {
        }

        protected open fun onRemoveData(data: E) {
        }

        protected open fun onRemoveData(data: List<E>) {
        }

        protected open fun onClearData() {
        }

        // LANraragi: E-Hentai parser overloads removed (GalleryListParser, FavoritesParser)

        fun onGetPageData(taskId: Int, pageCount: Int, nextPage: Int, data: List<E>) {
            if (currentTaskId == taskId) {
                when (currentTaskType) {
                    TYPE_REFRESH -> onTypeRefresh(pageCount, nextPage, data)
                    TYPE_PRE_PAGE, TYPE_PRE_PAGE_KEEP_POS -> onTypePrePage(pageCount, data)
                    TYPE_NEXT_PAGE, TYPE_NEXT_PAGE_KEEP_POS -> onTypeNextPage(pageCount, nextPage, data)
                    TYPE_SOMEWHERE -> onTypeSomewhere(pageCount, nextPage, data)
                    TYPE_REFRESH_PAGE -> onTypeRefreshPage(pageCount, nextPage, data)
                }
            }
        }

        private fun onTypeRefreshPage(pageCount: Int, nextPage: Int, data: List<E>) {
            if (currentTaskPage < startPage || currentTaskPage >= endPage) {
                Log.e(
                    TAG, "TYPE_REFRESH_PAGE, but currentTaskPage = " + currentTaskPage +
                        ", startPage = " + startPage + ", endPage = " + endPage
                )
                return
            }

            if (currentTaskPage == endPage - 1) {
                reportedNextPage = nextPage
            }

            pages = max(endPage, pageCount)

            val oldIndexStart = if (currentTaskPage == startPage) 0 else pageDivider.get(currentTaskPage - startPage - 1)
            val oldIndexEnd = pageDivider.get(currentTaskPage - startPage)
            // Snapshot of the refreshed page only: every other row is
            // untouched, so diffing just this window keeps the main-thread
            // cost at one page however far the list was scrolled.
            val oldWindow = ArrayList(rows.subList(oldIndexStart, oldIndexEnd))
            val toRemove = rows.subList(oldIndexStart, oldIndexEnd)
            onRemoveData(toRemove)
            toRemove.clear()
            val pageData = removeDuplicateData(data, oldIndexStart - CHECK_DUPLICATE_RANGE, oldIndexStart + CHECK_DUPLICATE_RANGE)
            val newIndexEnd = oldIndexStart + pageData.size
            rows.addAll(oldIndexStart, pageData)
            onAddData(pageData)
            dispatchWindowDiff(oldWindow, ArrayList(rows.subList(oldIndexStart, newIndexEnd)), oldIndexStart)

            for (i in currentTaskPage - startPage until pageDivider.size()) {
                pageDivider.set(i, pageDivider.get(i) - oldIndexEnd + newIndexEnd)
            }

            if (rows.isEmpty()) {
                // Ui change, show empty string
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                // Ui change, show content
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showContent()

                // RecyclerView scroll
                if (newIndexEnd > oldIndexEnd && newIndexEnd > 0 && recyclerView.isAttachedToWindow) {
                    recyclerView.stopScroll()
                    val manager = recyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, newIndexEnd - 1, 0)
                    onScrollToPosition(newIndexEnd - 1)
                }
            }
        }

        private fun onTypeSomewhere(pageCount: Int, nextPage: Int, data: List<E>) {
            startPage = currentTaskPage
            endPage = currentTaskPage + 1
            reportedNextPage = nextPage
            pages = pageCount
            pageDivider.clear()
            pageDivider.add(data.size)

            // Snapshot old data for DiffUtil comparison
            val oldSnapshot = ArrayList(rows)

            if (data.isEmpty()) {
                rows.clear()
                onClearData()
                dispatchDiffUpdates(oldSnapshot)

                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                rows.clear()
                onClearData()
                rows.addAll(data)
                onAddData(data)
                dispatchDiffUpdates(oldSnapshot)

                // Ui change, show content
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showContent()

                if (recyclerView.isAttachedToWindow) {
                    // RecyclerView scroll
                    recyclerView.stopScroll()
                    val manager = recyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                    onScrollToPosition(0)
                }
            }
        }

        private fun onTypeNextPage(pageCount: Int, nextPage: Int, data: List<E>) {
            val pageData = removeDuplicateData(data, rows.size - CHECK_DUPLICATE_RANGE, rows.size)
            val dataSize = pageData.size
            val oldDataSize = rows.size
            pageDivider.add(oldDataSize + dataSize)
            if (nextPage > -1 || pageCount > -1) {
                endPage++
                reportedNextPage = nextPage
                pages = max(endPage, pageCount)
            }

            if (pageData.isEmpty()) {
                if (rows.isEmpty()) {
                    // Ui change, show empty string
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(false)
                    showEmptyString()
                } else {
                    // Ui change, show content
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(false)
                    showContent()

                    if (currentTaskType == TYPE_NEXT_PAGE && recyclerView.isAttachedToWindow) {
                        // RecyclerView scroll
                        recyclerView.stopScroll()
                        val manager = recyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, oldDataSize, 0)
                        onScrollToPosition(oldDataSize)
                    }
                }
            } else {
                rows.addAll(pageData)
                onAddData(pageData)
                notifyItemRangeInserted(oldDataSize, dataSize)

                // Ui change, show content
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showContent()

                if (recyclerView.isAttachedToWindow) {
                    if (currentTaskType == TYPE_NEXT_PAGE_KEEP_POS) {
                        recyclerView.stopScroll()
                        recyclerView.smoothScrollBy(0, nextPageScrollSize)
                    } else {
                        recyclerView.stopScroll()
                        val manager = recyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, oldDataSize, 0)
                        onScrollToPosition(oldDataSize)
                    }
                }
            }
        }

        private fun onTypePrePage(pageCount: Int, data: List<E>) {
            val pageData = removeDuplicateData(data, 0, CHECK_DUPLICATE_RANGE)
            val dataSize = pageData.size
            for (i in 0 until pageDivider.size()) {
                pageDivider.set(i, pageDivider.get(i) + dataSize)
            }
            pageDivider.add(0, dataSize)
            startPage--
            pages = max(endPage, pageCount)
            // assert startPage >= 0

            if (pageData.isEmpty()) {
                if (rows.isEmpty()) {
                    // Ui change, show empty string
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(false)
                    showEmptyString()
                } else {
                    // Ui change, show content
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(false)
                    showContent()

                    if (currentTaskType == TYPE_PRE_PAGE && recyclerView.isAttachedToWindow) {
                        // RecyclerView scroll, to top
                        recyclerView.stopScroll()
                        val manager = recyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                        onScrollToPosition(0)
                    }
                }
            } else {
                rows.addAll(0, pageData)
                onAddData(pageData)
                notifyItemRangeInserted(0, pageData.size)

                // Ui change, show content
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showContent()

                if (recyclerView.isAttachedToWindow) {
                    // RecyclerView scroll
                    if (currentTaskType == TYPE_PRE_PAGE_KEEP_POS) {
                        recyclerView.stopScroll()
                        LayoutManagerUtils.scrollToPositionProperly(
                            recyclerView.layoutManager, requireContext(),
                            dataSize - 1, onScrollToPositionListener
                        )
                    } else {
                        recyclerView.stopScroll()
                        val manager = recyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                        onScrollToPosition(0)
                    }
                }
            }
        }

        private fun onTypeRefresh(pageCount: Int, nextPage: Int, data: List<E>) {
            startPage = 0
            endPage = 1
            pages = pageCount
            reportedNextPage = nextPage
            pageDivider.clear()
            pageDivider.add(data.size)

            // Snapshot before mutation for DiffUtil
            val oldSnapshot = ArrayList(rows)

            if (data.isEmpty()) {
                rows.clear()
                onClearData()
                dispatchDiffUpdates(oldSnapshot)
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                rows.clear()
                onClearData()
                rows.addAll(data)
                onAddData(data)
                dispatchDiffUpdates(oldSnapshot)

                // Ui change, show content
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)
                showContent()

                // RecyclerView scroll
                if (recyclerView.isAttachedToWindow) {
                    recyclerView.stopScroll()
                    val manager = recyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                    onScrollToPosition(0)
                }
            }
        }

        fun onGetException(taskId: Int, e: Exception?) {
            if (currentTaskId == taskId) {
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(false)

                val readableError: String
                if (e != null) {
                    e.printStackTrace()
                    // Route through the LRR-aware mapper so list/search failures
                    // show a localized message (auth, 404, timeout, cleartext
                    // refused, …) instead of a raw English socket/HTTP string.
                    readableError = friendlyError(requireContext(), e)
                } else {
                    readableError = requireContext().getString(R.string.error_unknown)
                }

                if (viewTransition.shownViewIndex == 0) {
                    Toast.makeText(getContext(), readableError, Toast.LENGTH_SHORT).show()
                } else {
                    showText(readableError)
                }
            }
        }

        fun showContent() {
            viewTransition.showView(0)
        }

        private fun isContentShowing(): Boolean {
            return viewTransition.shownViewIndex == 0
        }

        fun showProgressBar() {
            showProgressBar(true)
        }

        fun showProgressBar(animation: Boolean) {
            viewTransition.showView(1, animation)
        }

        fun showText(text: CharSequence?) {
            tipView.text = text
            viewTransition.showView(2)
        }

        fun showEmptyString() {
            showText(emptyString)
        }

        /**
         * Be careful
         */
        fun doGetData(type: Int, page: Int, refreshType: Int) {
            when (refreshType) {
                REFRESH_TYPE_FOOTER -> {
                    showContent()
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(true)
                }
                REFRESH_TYPE_PROGRESS_VIEW -> {
                    showProgressBar()
                    refreshLayout.setHeaderRefreshing(false)
                    refreshLayout.setFooterRefreshing(false)
                }
                // REFRESH_TYPE_HEADER and anything else
                else -> {
                    showContent()
                    refreshLayout.setFooterRefreshing(false)
                    refreshLayout.setHeaderRefreshing(true)
                }
            }

            currentTaskId = idGenerator.nextId()
            currentTaskType = type
            currentTaskPage = page
            getPageData(currentTaskId, currentTaskType, currentTaskPage)
        }

        private fun doRefresh() {
            currentTaskId = idGenerator.nextId()
            currentTaskType = TYPE_REFRESH
            currentTaskPage = 0
            getPageData(currentTaskId, currentTaskType, currentTaskPage)
        }

        /**
         * Like [refresh], but without animating to the progress bar
         */
        fun firstRefresh() {
            showProgressBar(false)
            doRefresh()
        }

        /**
         * Show the progress bar first, then refresh
         */
        fun refresh() {
            showProgressBar()
            doRefresh()
        }

        fun refreshSort(sort: String?) {
            showProgressBar()
            currentTaskId = idGenerator.nextId()
            currentTaskType = TYPE_REFRESH
            currentTaskPage = 0
            getPageData(currentTaskId, currentTaskType, currentTaskPage, sort)
        }

        protected fun cancelCurrentTask() {
            currentTaskId = idGenerator.nextId()
            refreshLayout.setHeaderRefreshing(false)
            refreshLayout.setFooterRefreshing(false)
        }

        private fun getPageStart(page: Int): Int {
            return if (startPage == page) {
                0
            } else {
                pageDivider.get(page - startPage - 1)
            }
        }

        private fun getPageForPosition(position: Int): Int {
            if (position < 0) {
                return -1
            }

            val pageDivider = pageDivider
            for (i in 0 until pageDivider.size()) {
                if (position < pageDivider.get(i)) {
                    return i + startPage
                }
            }

            return -1
        }

        val pageForTop: Int
            get() = getPageForPosition(LayoutManagerUtils.getFirstVisibleItemPosition(recyclerView.layoutManager))

        val pageForBottom: Int
            get() = getPageForPosition(LayoutManagerUtils.getLastVisibleItemPosition(recyclerView.layoutManager))

        fun canGoTo(): Boolean {
            return isContentShowing()
        }

        /**
         * Check range first!
         *
         * @param page the target page
         * @throws IndexOutOfBoundsException
         */
        fun goTo(page: Int) {
            if (page == -996) {
                refreshLayout.setFooterRefreshing(false)
                refreshLayout.setHeaderRefreshing(true)

                currentTaskId = idGenerator.nextId()
                currentTaskType = TYPE_SOMEWHERE
                currentTaskPage = page
                getExPageData(currentTaskType, currentTaskId, pages)
            } else if (page < 0 || page >= pages) {
                throw IndexOutOfBoundsException("Page count is $pages, page is $page")
            } else if (page >= startPage && page < endPage) {
                cancelCurrentTask()

                val position = getPageStart(page)
                recyclerView.stopScroll()
                LayoutManagerUtils.scrollToPositionWithOffset(recyclerView.layoutManager, position, 0)
                onScrollToPosition(position)
            } else if (page == startPage - 1) {
                refreshLayout.setFooterRefreshing(false)
                refreshLayout.setHeaderRefreshing(true)

                currentTaskId = idGenerator.nextId()
                currentTaskType = TYPE_PRE_PAGE
                currentTaskPage = page
                getPageData(currentTaskId, currentTaskType, currentTaskPage)
            } else if (page == endPage) {
                refreshLayout.setHeaderRefreshing(false)
                refreshLayout.setFooterRefreshing(true)

                currentTaskId = idGenerator.nextId()
                currentTaskType = TYPE_NEXT_PAGE
                currentTaskPage = page
                getPageData(currentTaskId, currentTaskType, currentTaskPage)
            } else {
                refreshLayout.setFooterRefreshing(false)
                refreshLayout.setHeaderRefreshing(true)

                currentTaskId = idGenerator.nextId()
                currentTaskType = TYPE_SOMEWHERE
                currentTaskPage = page
                getPageData(currentTaskId, currentTaskType, currentTaskPage)
            }
        }

        private var savedDataId = IntIdGenerator.INVALID_ID

        open fun saveInstanceState(superState: Parcelable?): Parcelable {
            val bundle = Bundle()
            bundle.putParcelable(KEY_SUPER, superState)
            val shownView = viewTransition.shownViewIndex
            bundle.putInt(KEY_SHOWN_VIEW, shownView)
            bundle.putString(KEY_TIP, tipView.text.toString())

            // KNOWN-ISSUE (P2): uses LRReaderApplication global state to persist data across config changes
            val app = requireContext().applicationContext as LRReaderApplication
            if (savedDataId != IntIdGenerator.INVALID_ID) {
                app.removeGlobalStuff(savedDataId)
                savedDataId = IntIdGenerator.INVALID_ID
            }
            savedDataId = app.putGlobalStuff(rows)
            bundle.putInt(KEY_DATA, savedDataId)

            bundle.putInt(KEY_NEXT_ID, idGenerator.nextId())
            bundle.putIntArray(KEY_PAGE_DIVIDER, pageDivider.toArray())
            bundle.putInt(KEY_START_PAGE, startPage)
            bundle.putInt(KEY_END_PAGE, endPage)
            bundle.putInt(KEY_PAGES, pages)
            bundle.putString(KEY_RESULT_COUNT, resultCount)
            bundle.putString(KEY_FIRST_HREF, firstHref)
            bundle.putString(KEY_PREV_HREF, prevHref)
            bundle.putString(KEY_NEXT_HREF, nextHref)
            bundle.putString(KEY_LAST_HREF, lastHref)
            return bundle
        }

        open fun restoreInstanceState(state: Parcelable): Parcelable? {
            if (state is Bundle) {
                viewTransition.showView(state.getInt(KEY_SHOWN_VIEW), false)
                tipView.text = state.getString(KEY_TIP)

                savedDataId = state.getInt(KEY_DATA)
                var newData: ArrayList<E>? = null
                val app = requireContext().applicationContext as LRReaderApplication
                if (savedDataId != IntIdGenerator.INVALID_ID) {
                    @Suppress("UNCHECKED_CAST") // saveInstanceState stored this helper's ArrayList<E>
                    newData = app.removeGlobalStuff(savedDataId) as ArrayList<E>?
                    savedDataId = IntIdGenerator.INVALID_ID
                    if (newData != null) {
                        rows = newData
                    }
                }

                idGenerator.setNextId(state.getInt(KEY_NEXT_ID))
                pageDivider = IntList.of(state.getIntArray(KEY_PAGE_DIVIDER))
                startPage = state.getInt(KEY_START_PAGE)
                endPage = state.getInt(KEY_END_PAGE)
                pages = state.getInt(KEY_PAGES)
                resultCount = state.getString(KEY_RESULT_COUNT)
                firstHref = state.getString(KEY_FIRST_HREF)
                prevHref = state.getString(KEY_PREV_HREF)
                nextHref = state.getString(KEY_NEXT_HREF)
                lastHref = state.getString(KEY_LAST_HREF)

                notifyDataSetChanged()

                if (newData == null) {
                    pageDivider.clear()
                    startPage = 0
                    endPage = 0
                    pages = 0
                    firstRefresh()
                }

                return BundleCompat.getParcelable(state, KEY_SUPER, Parcelable::class.java)
            } else {
                return state
            }
        }

        companion object {
            private val TAG = ContentHelper::class.java.simpleName

            private const val CHECK_DUPLICATE_RANGE = 50

            /** Past this many rows a whole-list update skips the diff (see dispatchDiffUpdates). */
            private const val MAX_FULL_DIFF = 200

            private const val KEY_SUPER = "super"
            private const val KEY_SHOWN_VIEW = "shown_view"
            private const val KEY_TIP = "tip"
            private const val KEY_DATA = "data"
            private const val KEY_NEXT_ID = "next_id"
            private const val KEY_PAGE_DIVIDER = "page_divider"
            private const val KEY_START_PAGE = "start_page"
            private const val KEY_END_PAGE = "end_page"
            private const val KEY_PAGES = "pages"
            private const val KEY_RESULT_COUNT = "result_count"
            private const val KEY_FIRST_HREF = "first_href"
            private const val KEY_PREV_HREF = "prev_href"
            private const val KEY_NEXT_HREF = "next_href"
            private const val KEY_LAST_HREF = "last_href"

            const val TYPE_REFRESH = 0
            const val TYPE_PRE_PAGE = 1
            const val TYPE_PRE_PAGE_KEEP_POS = 2
            const val TYPE_NEXT_PAGE = 3
            const val TYPE_NEXT_PAGE_KEEP_POS = 4
            const val TYPE_SOMEWHERE = 5
            const val TYPE_REFRESH_PAGE = 6

            const val REFRESH_TYPE_HEADER = 0
            const val REFRESH_TYPE_FOOTER = 1
            const val REFRESH_TYPE_PROGRESS_VIEW = 2

            const val GOTO_FIRST_PAGE = 0
            const val GOTO_PREV_PAGE = 1
            const val GOTO_NEXT_PAGE = 2
            const val GOTO_LAST_PAGE = 3
        }
    }
}
