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

    private lateinit var mProgressView: View
    private lateinit var mTipView: TextView
    private lateinit var mContentView: ViewGroup

    private lateinit var mRefreshLayout: ListRefreshLayout
    private lateinit var mRecyclerView: EasyRecyclerView
    private lateinit var mFastScroller: FastScroller

    private lateinit var mContentHelper: ContentHelper<*>

    private var mRecyclerViewOriginTop = 0
    private var mRecyclerViewOriginBottom = 0

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

        mProgressView = findViewById(R.id.progress)
        mTipView = findViewById(R.id.tip)
        mContentView = findViewById(R.id.content_view)

        mRefreshLayout = mContentView.findViewById(R.id.refresh_layout)
        mFastScroller = mContentView.findViewById(R.id.fast_scroller)
        mRecyclerView = mRefreshLayout.findViewById(R.id.recycler_view)

        mFastScroller.attachToRecyclerView(mRecyclerView)
        val drawable = HandlerDrawable()
        drawable.setColor(context.attrColor(R.attr.widgetColorThemeAccent))
        mFastScroller.setHandlerDrawable(drawable)

        mRefreshLayout.setColorSchemeResources(
            R.color.loading_indicator_red,
            R.color.loading_indicator_purple,
            R.color.loading_indicator_blue,
            R.color.loading_indicator_cyan,
            R.color.loading_indicator_green,
            R.color.loading_indicator_yellow
        )

        mRecyclerViewOriginTop = mRecyclerView.paddingTop
        mRecyclerViewOriginBottom = mRecyclerView.paddingBottom
    }

    val recyclerView: EasyRecyclerView
        get() = mRecyclerView

    val fastScroller: FastScroller
        get() = mFastScroller

    val refreshLayout: ListRefreshLayout
        get() = mRefreshLayout

    /**
     * Shows [loadingView] instead of the centred spinner while the
     * content loads (e.g. a skeleton of the list). Call before
     * [setHelper].
     */
    fun setLoadingView(loadingView: View) {
        val index = indexOfChild(mProgressView)
        removeView(mProgressView)
        loadingView.id = R.id.progress
        loadingView.visibility = mProgressView.visibility
        addView(loadingView, index, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mProgressView = loadingView
    }

    fun setHelper(helper: ContentHelper<*>?) {
        mContentHelper = requireNotNull(helper) { "ContentLayout needs a ContentHelper" }
        helper.init(this)
    }

    fun showFastScroll() {
        if (!mFastScroller.isAttached) {
            mFastScroller.attachToRecyclerView(mRecyclerView)
        }
    }

    fun hideFastScroll() {
        mFastScroller.detachedFromRecyclerView()
    }

    fun setFitPaddingTop(fitPaddingTop: Int) {
        // RecyclerView
        mRecyclerView.setPadding(
            mRecyclerView.paddingLeft, mRecyclerViewOriginTop + fitPaddingTop, mRecyclerView.paddingRight, mRecyclerView.paddingBottom
        )
        // RefreshLayout
        mRefreshLayout.setProgressViewOffset(false, fitPaddingTop, fitPaddingTop + ViewUtils.dp2pix(context, 32f))
    }

    fun setFitPaddingBottom(fitPaddingBottom: Int) {
        // RecyclerView
        mRecyclerView.setPadding(
            mRecyclerView.paddingLeft,
            mRecyclerView.paddingTop, mRecyclerView.paddingRight,
            mRecyclerViewOriginBottom + fitPaddingBottom
        )
    }

    override fun onSaveInstanceState(): Parcelable {
        return mContentHelper.saveInstanceState(super.onSaveInstanceState())
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        super.onRestoreInstanceState(mContentHelper.restoreInstanceState(state))
    }

    abstract class ContentHelper<E : Parcelable> : ViewTransition.OnShowViewListener {

        private lateinit var mProgressView: View
        private lateinit var mTipView: TextView
        private lateinit var mContentView: ViewGroup

        private lateinit var mRefreshLayout: ListRefreshLayout
        private lateinit var mRecyclerView: EasyRecyclerView

        private lateinit var mViewTransition: ViewTransition

        /**
         * Store data
         */
        private var mData = ArrayList<E>()

        /**
         * Generate task id
         */
        private val mIdGenerator = IntIdGenerator()

        /**
         * Store the page divider index
         *
         * For example, the data contain page 3, page 4, page 5,
         * page 3 size is 7, page 4 size is 8, page 5 size is 9,
         * so `mPageDivider` contain 7, 15, 24.
         */
        private var mPageDivider = IntList()

        /**
         * The first page in `mData`
         */
        private var mStartPage = 0

        /**
         * The last page + 1 in `mData`
         */
        private var mEndPage = 0

        /**
         * The available page count.
         */
        var mPages = 0

        private var mNextPage = 0

        var resultCount: String? = null
        var firstHref: String? = null
        var prevHref: String? = null
        var nextHref: String? = null
        var lastHref: String? = null

        private var mCurrentTaskId = 0
        private var mCurrentTaskType = 0
        private var mCurrentTaskPage = 0

        private var mNextPageScrollSize = 0

        private var mEmptyString: String? = "No hint"

        private val mOnScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!mRefreshLayout.isAnyRefreshing && mRefreshLayout.isAlmostBottom() && hasMorePages()) {
                    // Get next page
                    mRefreshLayout.setFooterRefreshing(true)
                    mOnRefreshListener.onFooterRefresh()
                }
            }
        }

        /** Another page may follow the loaded ones (or the page count is unknown). */
        private fun hasMorePages(): Boolean = mEndPage < mPages || mNextPage == -1 || mPages == -1

        private val mOnRefreshListener = object : ListRefreshLayout.OnRefreshListener {
            override fun onHeaderRefresh() {
                if (mStartPage > 0) {
                    mCurrentTaskId = mIdGenerator.nextId()
                    mCurrentTaskType = TYPE_PRE_PAGE_KEEP_POS
                    mCurrentTaskPage = mStartPage - 1
                    getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
                } else if (!prevHref.isNullOrEmpty()) {
                    mCurrentTaskId = mIdGenerator.nextId()
                    mCurrentTaskType = TYPE_PRE_PAGE_KEEP_POS
                    getExPageData(GOTO_PREV_PAGE, mCurrentTaskId, mCurrentTaskPage)
                } else {
                    doRefresh()
                }
            }

            override fun onFooterRefresh() {
                if (mEndPage == -1 || mPages == -1) {
                    mCurrentTaskId = mIdGenerator.nextId()
                    mCurrentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                    getExPageData(GOTO_NEXT_PAGE, mCurrentTaskId, mPages)
                } else if (mEndPage < mPages) {
                    // Get next page
                    // Fill pages before NextPage with empty list
                    while (mNextPage > mEndPage && mEndPage < mPages) {
                        mCurrentTaskId = mIdGenerator.nextId()
                        mCurrentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                        mCurrentTaskPage = mEndPage
                        onGetPageData(mCurrentTaskId, mPages, mNextPage, emptyList())
                    }
                    mCurrentTaskId = mIdGenerator.nextId()
                    mCurrentTaskType = TYPE_NEXT_PAGE_KEEP_POS
                    mCurrentTaskPage = mEndPage
                    getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
                } else if (mEndPage == mPages) {
                    // Refresh last page
                    mCurrentTaskId = mIdGenerator.nextId()
                    mCurrentTaskType = TYPE_REFRESH_PAGE
                    mCurrentTaskPage = mEndPage - 1
                    getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
                } else {
                    Log.e(TAG, "Try to footer refresh, but mEndPage = $mEndPage, mPages = $mPages")
                    mRefreshLayout.setFooterRefreshing(false)
                }
            }
        }

        private val mOnScrollToPositionListener =
            LayoutManagerUtils.OnScrollToPositionListener { position -> onScrollToPosition(position) }

        // Called by ContentLayout.setHelper; the stable JVM name keeps the baseline-profile rule valid.
        @JvmName("init")
        internal fun init(contentLayout: ContentLayout) {
            mNextPageScrollSize = ViewUtils.dp2pix(contentLayout.context, 48f)

            mProgressView = contentLayout.mProgressView
            mTipView = contentLayout.mTipView
            mContentView = contentLayout.mContentView

            mRefreshLayout = contentLayout.mRefreshLayout
            mRecyclerView = contentLayout.mRecyclerView

            val drawable = checkNotNull(ContextCompat.getDrawable(requireContext(), R.drawable.big_sad_pandroid))
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            mTipView.setCompoundDrawables(null, drawable, null, null)

            mViewTransition = ViewTransition(mContentView, mProgressView, mTipView)
            mViewTransition.onShowViewListener = this

            mRecyclerView.addOnScrollListener(mOnScrollListener)
            mRefreshLayout.setOnRefreshListener(mOnRefreshListener)

            // Tap the tip to reload
            mTipView.setOnClickListener { refresh() }
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

        protected open fun onScrollToPosition(postion: Int) {
        }

        override fun onShowView(hiddenView: View, shownView: View) {
            // Optional hook for subclasses.
        }

        val shownViewIndex: Int
            get() = mViewTransition.shownViewIndex

        fun setRefreshLayoutEnable(enable: Boolean) {
            mRefreshLayout.isEnabled = enable
        }

        fun setEnable(enable: Boolean) {
            mRefreshLayout.isEnabled = enable
        }

        fun setEmptyString(str: String?) {
            mEmptyString = str
        }

        fun getData(): MutableList<E> {
            return mData
        }

        /**
         * @throws IndexOutOfBoundsException if `location < 0 || location >= size()`
         */
        fun getDataAt(location: Int): E {
            return mData[location]
        }

        fun getDataAtEx(location: Int): E? {
            return if (location >= 0 && location < mData.size) {
                mData[location]
            } else {
                null
            }
        }

        fun size(): Int {
            return mData.size
        }

        fun isCurrentTask(taskId: Int): Boolean {
            return mCurrentTaskId == taskId
        }

        fun getPages(): Int {
            return mPages
        }

        fun addAt(index: Int, data: E) {
            mData.add(index, data)
            onAddData(data)

            for (i in 0 until mPageDivider.size()) {
                val divider = mPageDivider.get(i)
                if (index < divider) {
                    mPageDivider.set(i, divider + 1)
                }
            }

            notifyItemRangeInserted(index, 1)
        }

        fun removeAt(index: Int) {
            val data = mData.removeAt(index)
            onRemoveData(data)

            for (i in 0 until mPageDivider.size()) {
                val divider = mPageDivider.get(i)
                if (index < divider) {
                    mPageDivider.set(i, divider - 1)
                }
            }

            notifyItemRangeRemoved(index, 1)
        }

        protected abstract fun isDuplicate(d1: E, d2: E): Boolean

        /**
         * Compute the difference between `oldData` and the current `mData`,
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
            if (oldData.size > MAX_FULL_DIFF || mData.size > MAX_FULL_DIFF) {
                if (oldData.isNotEmpty()) notifyItemRangeRemoved(0, oldData.size)
                if (mData.isNotEmpty()) notifyItemRangeInserted(0, mData.size)
                return
            }
            dispatchWindowDiff(oldData, mData, 0)
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
         * [data] without the rows that duplicate one of `mData[start, end)`.
         * Returns a copy: Kotlin callers may hand in read-only lists.
         */
        private fun removeDuplicateData(data: List<E>, start: Int, end: Int): List<E> {
            val from = max(0, start)
            val to = min(mData.size, end)
            return data.filterNot { d ->
                var duplicate = false
                for (i in from until to) {
                    if (isDuplicate(d, mData[i])) {
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

        fun onGetPageData(taskId: Int, pages: Int, nextPage: Int, data: List<E>) {
            if (mCurrentTaskId == taskId) {
                when (mCurrentTaskType) {
                    TYPE_REFRESH -> onTypeRefresh(pages, nextPage, data)
                    TYPE_PRE_PAGE, TYPE_PRE_PAGE_KEEP_POS -> onTypeRpePage(pages, data)
                    TYPE_NEXT_PAGE, TYPE_NEXT_PAGE_KEEP_POS -> onTypeNextPage(pages, nextPage, data)
                    TYPE_SOMEWHERE -> onTypeSomeWhere(pages, nextPage, data)
                    TYPE_REFRESH_PAGE -> onTypeRefreshPage(pages, nextPage, data)
                }
            }
        }

        private fun onTypeRefreshPage(pages: Int, nextPage: Int, data: List<E>) {
            if (mCurrentTaskPage < mStartPage || mCurrentTaskPage >= mEndPage) {
                Log.e(
                    TAG, "TYPE_REFRESH_PAGE, but mCurrentTaskPage = " + mCurrentTaskPage +
                        ", mStartPage = " + mStartPage + ", mEndPage = " + mEndPage
                )
                return
            }

            if (mCurrentTaskPage == mEndPage - 1) {
                mNextPage = nextPage
            }

            mPages = max(mEndPage, pages)

            val oldIndexStart = if (mCurrentTaskPage == mStartPage) 0 else mPageDivider.get(mCurrentTaskPage - mStartPage - 1)
            val oldIndexEnd = mPageDivider.get(mCurrentTaskPage - mStartPage)
            // Snapshot of the refreshed page only: every other row is
            // untouched, so diffing just this window keeps the main-thread
            // cost at one page however far the list was scrolled.
            val oldWindow = ArrayList(mData.subList(oldIndexStart, oldIndexEnd))
            val toRemove = mData.subList(oldIndexStart, oldIndexEnd)
            onRemoveData(toRemove)
            toRemove.clear()
            val pageData = removeDuplicateData(data, oldIndexStart - CHECK_DUPLICATE_RANGE, oldIndexStart + CHECK_DUPLICATE_RANGE)
            val newIndexEnd = oldIndexStart + pageData.size
            mData.addAll(oldIndexStart, pageData)
            onAddData(pageData)
            dispatchWindowDiff(oldWindow, ArrayList(mData.subList(oldIndexStart, newIndexEnd)), oldIndexStart)

            for (i in mCurrentTaskPage - mStartPage until mPageDivider.size()) {
                mPageDivider.set(i, mPageDivider.get(i) - oldIndexEnd + newIndexEnd)
            }

            if (mData.isEmpty()) {
                // Ui change, show empty string
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                // Ui change, show content
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showContent()

                // RecyclerView scroll
                if (newIndexEnd > oldIndexEnd && newIndexEnd > 0 && mRecyclerView.isAttachedToWindow) {
                    mRecyclerView.stopScroll()
                    val manager = mRecyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, newIndexEnd - 1, 0)
                    onScrollToPosition(newIndexEnd - 1)
                }
            }
        }

        private fun onTypeSomeWhere(pages: Int, nextPage: Int, data: List<E>) {
            mStartPage = mCurrentTaskPage
            mEndPage = mCurrentTaskPage + 1
            mNextPage = nextPage
            mPages = pages
            mPageDivider.clear()
            mPageDivider.add(data.size)

            // Snapshot old data for DiffUtil comparison
            val oldSnapshot = ArrayList(mData)

            if (data.isEmpty()) {
                mData.clear()
                onClearData()
                dispatchDiffUpdates(oldSnapshot)

                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                mData.clear()
                onClearData()
                mData.addAll(data)
                onAddData(data)
                dispatchDiffUpdates(oldSnapshot)

                // Ui change, show content
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showContent()

                if (mRecyclerView.isAttachedToWindow) {
                    // RecyclerView scroll
                    mRecyclerView.stopScroll()
                    val manager = mRecyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                    onScrollToPosition(0)
                }
            }
        }

        private fun onTypeNextPage(pages: Int, nextPage: Int, data: List<E>) {
            val pageData = removeDuplicateData(data, mData.size - CHECK_DUPLICATE_RANGE, mData.size)
            val dataSize = pageData.size
            val oldDataSize = mData.size
            mPageDivider.add(oldDataSize + dataSize)
            if (nextPage > -1 || pages > -1) {
                mEndPage++
                mNextPage = nextPage
                mPages = max(mEndPage, pages)
            }

            if (pageData.isEmpty()) {
                if (mData.isEmpty()) {
                    // Ui change, show empty string
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(false)
                    showEmptyString()
                } else {
                    // Ui change, show content
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(false)
                    showContent()

                    if (mCurrentTaskType == TYPE_NEXT_PAGE && mRecyclerView.isAttachedToWindow) {
                        // RecyclerView scroll
                        mRecyclerView.stopScroll()
                        val manager = mRecyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, oldDataSize, 0)
                        onScrollToPosition(oldDataSize)
                    }
                }
            } else {
                mData.addAll(pageData)
                onAddData(pageData)
                notifyItemRangeInserted(oldDataSize, dataSize)

                // Ui change, show content
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showContent()

                if (mRecyclerView.isAttachedToWindow) {
                    if (mCurrentTaskType == TYPE_NEXT_PAGE_KEEP_POS) {
                        mRecyclerView.stopScroll()
                        mRecyclerView.smoothScrollBy(0, mNextPageScrollSize)
                    } else {
                        mRecyclerView.stopScroll()
                        val manager = mRecyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, oldDataSize, 0)
                        onScrollToPosition(oldDataSize)
                    }
                }
            }
        }

        private fun onTypeRpePage(pages: Int, data: List<E>) {
            val pageData = removeDuplicateData(data, 0, CHECK_DUPLICATE_RANGE)
            val dataSize = pageData.size
            for (i in 0 until mPageDivider.size()) {
                mPageDivider.set(i, mPageDivider.get(i) + dataSize)
            }
            mPageDivider.add(0, dataSize)
            mStartPage--
            mPages = max(mEndPage, pages)
            // assert mStartPage >= 0

            if (pageData.isEmpty()) {
                if (mData.isEmpty()) {
                    // Ui change, show empty string
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(false)
                    showEmptyString()
                } else {
                    // Ui change, show content
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(false)
                    showContent()

                    if (mCurrentTaskType == TYPE_PRE_PAGE && mRecyclerView.isAttachedToWindow) {
                        // RecyclerView scroll, to top
                        mRecyclerView.stopScroll()
                        val manager = mRecyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                        onScrollToPosition(0)
                    }
                }
            } else {
                mData.addAll(0, pageData)
                onAddData(pageData)
                notifyItemRangeInserted(0, pageData.size)

                // Ui change, show content
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showContent()

                if (mRecyclerView.isAttachedToWindow) {
                    // RecyclerView scroll
                    if (mCurrentTaskType == TYPE_PRE_PAGE_KEEP_POS) {
                        mRecyclerView.stopScroll()
                        LayoutManagerUtils.scrollToPositionProperly(
                            mRecyclerView.layoutManager, requireContext(),
                            dataSize - 1, mOnScrollToPositionListener
                        )
                    } else {
                        mRecyclerView.stopScroll()
                        val manager = mRecyclerView.layoutManager ?: return
                        LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                        onScrollToPosition(0)
                    }
                }
            }
        }

        private fun onTypeRefresh(pages: Int, nextPage: Int, data: List<E>) {
            mStartPage = 0
            mEndPage = 1
            mPages = pages
            mNextPage = nextPage
            mPageDivider.clear()
            mPageDivider.add(data.size)

            // Snapshot before mutation for DiffUtil
            val oldSnapshot = ArrayList(mData)

            if (data.isEmpty()) {
                mData.clear()
                onClearData()
                dispatchDiffUpdates(oldSnapshot)
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showEmptyString()
            } else {
                mData.clear()
                onClearData()
                mData.addAll(data)
                onAddData(data)
                dispatchDiffUpdates(oldSnapshot)

                // Ui change, show content
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)
                showContent()

                // RecyclerView scroll
                if (mRecyclerView.isAttachedToWindow) {
                    mRecyclerView.stopScroll()
                    val manager = mRecyclerView.layoutManager ?: return
                    LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                    onScrollToPosition(0)
                }
            }
        }

        fun onGetException(taskId: Int, e: Exception?) {
            if (mCurrentTaskId == taskId) {
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(false)

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

                if (mViewTransition.shownViewIndex == 0) {
                    Toast.makeText(getContext(), readableError, Toast.LENGTH_SHORT).show()
                } else {
                    showText(readableError)
                }
            }
        }

        fun showContent() {
            mViewTransition.showView(0)
        }

        private fun isContentShowing(): Boolean {
            return mViewTransition.shownViewIndex == 0
        }

        fun showProgressBar() {
            showProgressBar(true)
        }

        fun showProgressBar(animation: Boolean) {
            mViewTransition.showView(1, animation)
        }

        fun showText(text: CharSequence?) {
            mTipView.text = text
            mViewTransition.showView(2)
        }

        fun showEmptyString() {
            showText(mEmptyString)
        }

        /**
         * Be carefull
         */
        fun doGetData(type: Int, page: Int, refreshType: Int) {
            when (refreshType) {
                REFRESH_TYPE_FOOTER -> {
                    showContent()
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(true)
                }
                REFRESH_TYPE_PROGRESS_VIEW -> {
                    showProgressBar()
                    mRefreshLayout.setHeaderRefreshing(false)
                    mRefreshLayout.setFooterRefreshing(false)
                }
                // REFRESH_TYPE_HEADER and anything else
                else -> {
                    showContent()
                    mRefreshLayout.setFooterRefreshing(false)
                    mRefreshLayout.setHeaderRefreshing(true)
                }
            }

            mCurrentTaskId = mIdGenerator.nextId()
            mCurrentTaskType = type
            mCurrentTaskPage = page
            getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
        }

        private fun doRefresh() {
            mCurrentTaskId = mIdGenerator.nextId()
            mCurrentTaskType = TYPE_REFRESH
            mCurrentTaskPage = 0
            getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
        }

        /**
         * Lisk [refresh], but no animation when show progress bar
         */
        fun firstRefresh() {
            showProgressBar(false)
            doRefresh()
        }

        /**
         * Show progress bar first, than do refresh
         */
        fun refresh() {
            showProgressBar()
            doRefresh()
        }

        fun refreshSort(sort: String?) {
            showProgressBar()
            mCurrentTaskId = mIdGenerator.nextId()
            mCurrentTaskType = TYPE_REFRESH
            mCurrentTaskPage = 0
            getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage, sort)
        }

        protected fun cancelCurrentTask() {
            mCurrentTaskId = mIdGenerator.nextId()
            mRefreshLayout.setHeaderRefreshing(false)
            mRefreshLayout.setFooterRefreshing(false)
        }

        private fun getPageStart(page: Int): Int {
            return if (mStartPage == page) {
                0
            } else {
                mPageDivider.get(page - mStartPage - 1)
            }
        }

        private fun getPageForPosition(position: Int): Int {
            if (position < 0) {
                return -1
            }

            val pageDivider = mPageDivider
            for (i in 0 until pageDivider.size()) {
                if (position < pageDivider.get(i)) {
                    return i + mStartPage
                }
            }

            return -1
        }

        fun getPageForTop(): Int {
            return getPageForPosition(LayoutManagerUtils.getFirstVisibleItemPosition(mRecyclerView.layoutManager))
        }

        fun getPageForBottom(): Int {
            return getPageForPosition(LayoutManagerUtils.getLastVisibleItemPosition(mRecyclerView.layoutManager))
        }

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
                mRefreshLayout.setFooterRefreshing(false)
                mRefreshLayout.setHeaderRefreshing(true)

                mCurrentTaskId = mIdGenerator.nextId()
                mCurrentTaskType = TYPE_SOMEWHERE
                mCurrentTaskPage = page
                getExPageData(mCurrentTaskType, mCurrentTaskId, mPages)
            } else if (page < 0 || page >= mPages) {
                throw IndexOutOfBoundsException("Page count is $mPages, page is $page")
            } else if (page >= mStartPage && page < mEndPage) {
                cancelCurrentTask()

                val position = getPageStart(page)
                mRecyclerView.stopScroll()
                LayoutManagerUtils.scrollToPositionWithOffset(mRecyclerView.layoutManager, position, 0)
                onScrollToPosition(position)
            } else if (page == mStartPage - 1) {
                mRefreshLayout.setFooterRefreshing(false)
                mRefreshLayout.setHeaderRefreshing(true)

                mCurrentTaskId = mIdGenerator.nextId()
                mCurrentTaskType = TYPE_PRE_PAGE
                mCurrentTaskPage = page
                getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
            } else if (page == mEndPage) {
                mRefreshLayout.setHeaderRefreshing(false)
                mRefreshLayout.setFooterRefreshing(true)

                mCurrentTaskId = mIdGenerator.nextId()
                mCurrentTaskType = TYPE_NEXT_PAGE
                mCurrentTaskPage = page
                getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
            } else {
                mRefreshLayout.setFooterRefreshing(false)
                mRefreshLayout.setHeaderRefreshing(true)

                mCurrentTaskId = mIdGenerator.nextId()
                mCurrentTaskType = TYPE_SOMEWHERE
                mCurrentTaskPage = page
                getPageData(mCurrentTaskId, mCurrentTaskType, mCurrentTaskPage)
            }
        }

        private var mSavedDataId = IntIdGenerator.INVALID_ID

        open fun saveInstanceState(superState: Parcelable?): Parcelable {
            val bundle = Bundle()
            bundle.putParcelable(KEY_SUPER, superState)
            val shownView = mViewTransition.shownViewIndex
            bundle.putInt(KEY_SHOWN_VIEW, shownView)
            bundle.putString(KEY_TIP, mTipView.text.toString())

            // KNOWN-ISSUE (P2): uses LRReaderApplication global state to persist data across config changes
            val app = requireContext().applicationContext as LRReaderApplication
            if (mSavedDataId != IntIdGenerator.INVALID_ID) {
                app.removeGlobalStuff(mSavedDataId)
                mSavedDataId = IntIdGenerator.INVALID_ID
            }
            mSavedDataId = app.putGlobalStuff(mData)
            bundle.putInt(KEY_DATA, mSavedDataId)

            bundle.putInt(KEY_NEXT_ID, mIdGenerator.nextId())
            bundle.putIntArray(KEY_PAGE_DIVIDER, mPageDivider.toArray())
            bundle.putInt(KEY_START_PAGE, mStartPage)
            bundle.putInt(KEY_END_PAGE, mEndPage)
            bundle.putInt(KEY_PAGES, mPages)
            bundle.putString(KEY_RESULT_COUNT, resultCount)
            bundle.putString(KEY_FIRST_HREF, firstHref)
            bundle.putString(KEY_PREV_HREF, prevHref)
            bundle.putString(KEY_NEXT_HREF, nextHref)
            bundle.putString(KEY_LAST_HREF, lastHref)
            return bundle
        }

        open fun restoreInstanceState(state: Parcelable): Parcelable? {
            if (state is Bundle) {
                mViewTransition.showView(state.getInt(KEY_SHOWN_VIEW), false)
                mTipView.text = state.getString(KEY_TIP)

                mSavedDataId = state.getInt(KEY_DATA)
                var newData: ArrayList<E>? = null
                val app = requireContext().applicationContext as LRReaderApplication
                if (mSavedDataId != IntIdGenerator.INVALID_ID) {
                    @Suppress("UNCHECKED_CAST") // saveInstanceState stored this helper's ArrayList<E>
                    newData = app.removeGlobalStuff(mSavedDataId) as ArrayList<E>?
                    mSavedDataId = IntIdGenerator.INVALID_ID
                    if (newData != null) {
                        mData = newData
                    }
                }

                mIdGenerator.setNextId(state.getInt(KEY_NEXT_ID))
                mPageDivider = IntList.of(state.getIntArray(KEY_PAGE_DIVIDER))
                mStartPage = state.getInt(KEY_START_PAGE)
                mEndPage = state.getInt(KEY_END_PAGE)
                mPages = state.getInt(KEY_PAGES)
                resultCount = state.getString(KEY_RESULT_COUNT)
                firstHref = state.getString(KEY_FIRST_HREF)
                prevHref = state.getString(KEY_PREV_HREF)
                nextHref = state.getString(KEY_NEXT_HREF)
                lastHref = state.getString(KEY_LAST_HREF)

                notifyDataSetChanged()

                if (newData == null) {
                    mPageDivider.clear()
                    mStartPage = 0
                    mEndPage = 0
                    mPages = 0
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
