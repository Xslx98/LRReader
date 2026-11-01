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

@file:Suppress( // TODO(2.4-idiom)
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "LargeClass",
    "EmptyFunctionBlock",
    "UnusedParameter",
)

package com.lanraragi.framework.lib.glgallery

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.IntDef
import androidx.core.util.Pools
import com.lanraragi.framework.lib.glview.annotation.RenderThread
import com.lanraragi.framework.lib.glview.glrenderer.BasicTexture
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.StringTexture
import com.lanraragi.framework.lib.glview.image.ImageMovableTextTexture
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.view.AnimationTime
import com.lanraragi.framework.lib.glview.view.GLRoot
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.widget.GLEdgeView
import com.lanraragi.framework.lib.glview.widget.GLProgressView
import com.lanraragi.framework.lib.glview.widget.GLTextureView
import com.lanraragi.reader.util.MathUtils
import java.util.Collections

class GalleryView private constructor(build: Builder) : GLView(), GestureRecognizer.Listener {

    @IntDef(LAYOUT_LEFT_TO_RIGHT, LAYOUT_RIGHT_TO_LEFT, LAYOUT_TOP_TO_BOTTOM)
    @Retention(AnnotationRetention.SOURCE)
    annotation class LayoutMode

    @IntDef(SCALE_ORIGIN, SCALE_FIT_WIDTH, SCALE_FIT_HEIGHT, SCALE_FIT, SCALE_FIXED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class ScaleMode

    @IntDef(
        START_POSITION_TOP_LEFT, START_POSITION_TOP_RIGHT, START_POSITION_BOTTOM_LEFT,
        START_POSITION_BOTTOM_RIGHT, START_POSITION_CENTER,
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class StartPosition

    private val mContext: Context = build.mContext
    private var mAdapter: Adapter? = build.mAdapter
    private val mGestureRecognizer: GestureRecognizer
    private val mListener: Listener? = build.mListener

    private var mPageTextTexture: ImageMovableTextTexture? = null

    private var mPagerLayoutManager: PagerLayoutManager? = null
    private var mScrollLayoutManager: ScrollLayoutManager? = null
    private var mLayoutManager: LayoutManager? = null

    private val mTransformScratch: MutableList<PageTransform> = ArrayList()

    @Volatile
    private var mPageTransforms: List<PageTransform> = Collections.emptyList()

    // Off by default: matches the stamps overlay's hidden default; see
    // setPageTransformCollectionEnabled(boolean).
    @Volatile
    private var mPageTransformCollectionEnabled = false

    private val mEdgeView: GLEdgeView
    private val mGalleryPageViewPool = Pools.SimplePool<GalleryPageView>(5)
    private var mProgressCache: GLProgressView? = null
    private var mErrorViewCache: GLTextureView? = null

    private val mBackgroundColor: Int = build.mBackgroundColor
    private var mPagerInterval: Int = build.mPagerInterval
    private var mScrollInterval: Int = build.mScrollInterval
    private val mPageMinHeight: Int = build.mPageMinHeight
    private val mPageInfoInterval: Int = build.mPageInfoInterval
    private val mProgressColor: Int = build.mProgressColor
    private val mProgressSize: Int = build.mProgressSize
    private val mPageTextColor: Int = build.mPageTextColor
    private val mPageTextSize: Int = build.mPageTextSize
    private val mPageTextTypeface: Typeface = build.mPageTextTypeface
    private val mErrorTextSize: Int = build.mErrorTextSize
    private val mErrorTextColor: Int = build.mErrorTextColor

    private val mDefaultErrorString: String = build.mDefaultErrorString
    private val mEmptyString: String = build.mEmptyString

    private var mEnableRequestFill = true
    private var mRequestFill = false
    private var mWillFill = false

    private var mScale = false
    private var mScroll = false
    private var mFirstScroll = false

    private val mLeftArea = Rect()
    private val mRightArea = Rect()
    private val mMenuArea = Rect()
    private val mSliderArea = Rect()

    private var mLayoutMode: Int = build.mLayoutMode
    private var mScaleMode: Int = build.mScaleMode
    private var mStartPosition: Int = build.mStartPosition
    private var mIndex: Int = MathUtils.clamp(build.mStartPage, 0, Int.MAX_VALUE)

    private val mMethodList: MutableList<Int> = ArrayList(5)
    private val mArgsList: MutableList<Array<out Any>> = ArrayList(5)
    private val mMethodListTemp: MutableList<Int> = ArrayList(5)
    private val mArgsListTemp: MutableList<Array<out Any>> = ArrayList(5)

    // Render-thread only: the last index reported to onUpdateCurrentIndex.
    private var mCurrentIndex = GalleryPageView.INVALID_INDEX

    // Latest intra-page scroll fraction published from the render
    // thread (after each fill). Read by UI-thread callers via
    // getCurrentScrollFraction() to persist a precise resume point
    // on exit. Always 0 for pager modes.
    @Volatile
    private var mCurrentScrollFraction = 0f

    init {
        build.mAdapter.setGalleryView(this)
        mGestureRecognizer = GestureRecognizer(mContext, this)
        mEdgeView = GLEdgeView(build.mEdgeColor)

        backgroundColor = mBackgroundColor
    }

    class Builder(context: Context, adapter: Adapter) {

        @JvmField internal val mContext: Context = context

        @JvmField internal val mAdapter: Adapter = adapter

        @JvmField internal var mListener: Listener? = null

        @JvmField internal var mLayoutMode = LAYOUT_LEFT_TO_RIGHT

        @JvmField internal var mScaleMode = SCALE_FIT

        @JvmField internal var mStartPosition = START_POSITION_TOP_LEFT

        @JvmField internal var mStartPage = 0

        @JvmField internal var mBackgroundColor = Color.BLACK

        @JvmField internal var mEdgeColor = Color.WHITE

        @JvmField internal var mPagerInterval = 48

        @JvmField internal var mScrollInterval = 24

        @JvmField internal var mPageMinHeight = 256

        @JvmField internal var mPageInfoInterval = 24

        @JvmField internal var mProgressColor = Color.WHITE

        @JvmField internal var mProgressSize = 56

        @JvmField internal var mPageTextColor = Color.WHITE

        @JvmField internal var mPageTextSize = 56

        @JvmField internal var mPageTextTypeface: Typeface = Typeface.DEFAULT

        @JvmField internal var mErrorTextColor = Color.RED

        @JvmField internal var mErrorTextSize = 24

        @JvmField internal var mDefaultErrorString = "Error"

        @JvmField internal var mEmptyString = "Empty"

        fun setListener(listener: Listener?): Builder {
            mListener = listener
            return this
        }

        fun setLayoutMode(@LayoutMode layoutMode: Int): Builder {
            mLayoutMode = layoutMode
            return this
        }

        fun setScaleMode(@ScaleMode scaleMode: Int): Builder {
            mScaleMode = scaleMode
            return this
        }

        fun setStartPosition(@StartPosition startPosition: Int): Builder {
            mStartPosition = startPosition
            return this
        }

        fun setStartPage(startPage: Int): Builder {
            mStartPage = startPage
            return this
        }

        fun setBackgroundColor(backgroundColor: Int): Builder {
            mBackgroundColor = backgroundColor
            return this
        }

        fun setEdgeColor(edgeColor: Int): Builder {
            mEdgeColor = edgeColor
            return this
        }

        fun setPagerInterval(pagerInterval: Int): Builder {
            mPagerInterval = pagerInterval
            return this
        }

        fun setScrollInterval(scrollInterval: Int): Builder {
            mScrollInterval = scrollInterval
            return this
        }

        fun setPageMinHeight(pageMinHeight: Int): Builder {
            mPageMinHeight = pageMinHeight
            return this
        }

        fun setPageInfoInterval(pageInfoInterval: Int): Builder {
            mPageInfoInterval = pageInfoInterval
            return this
        }

        fun setProgressColor(progressColor: Int): Builder {
            mProgressColor = progressColor
            return this
        }

        fun setProgressSize(progressSize: Int): Builder {
            mProgressSize = progressSize
            return this
        }

        fun setPageTextColor(pageTextColor: Int): Builder {
            mPageTextColor = pageTextColor
            return this
        }

        fun setPageTextSize(pageTextSize: Int): Builder {
            mPageTextSize = pageTextSize
            return this
        }

        fun setPageTextTypeface(pageTextTypeface: Typeface): Builder {
            mPageTextTypeface = pageTextTypeface
            return this
        }

        fun setErrorTextColor(errorTextColor: Int): Builder {
            mErrorTextColor = errorTextColor
            return this
        }

        fun setErrorTextSize(errorTextSize: Int): Builder {
            mErrorTextSize = errorTextSize
            return this
        }

        fun setDefaultErrorString(defaultErrorString: String): Builder {
            mDefaultErrorString = defaultErrorString
            return this
        }

        fun setEmptyString(emptyString: String): Builder {
            mEmptyString = emptyString
            return this
        }

        fun build(): GalleryView = GalleryView(this)
    }

    private fun ensurePagerLayoutManager(): PagerLayoutManager {
        var pagerLayoutManager = mPagerLayoutManager
        if (pagerLayoutManager == null) {
            pagerLayoutManager = PagerLayoutManager(
                mContext, this,
                mScaleMode, mStartPosition, 1.0f, mPagerInterval,
            )
            mPagerLayoutManager = pagerLayoutManager
        }
        return pagerLayoutManager
    }

    private fun ensureScrollLayoutManager(): ScrollLayoutManager {
        var scrollLayoutManager = mScrollLayoutManager
        if (scrollLayoutManager == null) {
            scrollLayoutManager = ScrollLayoutManager(mContext, this, mScrollInterval)
            mScrollLayoutManager = scrollLayoutManager
        }
        return scrollLayoutManager
    }

    private fun attachLayoutManager() {
        if (null != mLayoutManager) {
            return
        }

        when (mLayoutMode) {
            LAYOUT_LEFT_TO_RIGHT -> {
                val pagerLayoutManager = ensurePagerLayoutManager()
                pagerLayoutManager.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                pagerLayoutManager.onAttach(mAdapter)
                pagerLayoutManager.setCurrentIndex(mIndex)
                mAdapter = null
                mLayoutManager = pagerLayoutManager
            }
            LAYOUT_RIGHT_TO_LEFT -> {
                val pagerLayoutManager = ensurePagerLayoutManager()
                pagerLayoutManager.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                pagerLayoutManager.onAttach(mAdapter)
                pagerLayoutManager.setCurrentIndex(mIndex)
                mAdapter = null
                mLayoutManager = pagerLayoutManager
            }
            LAYOUT_TOP_TO_BOTTOM -> {
                val scrollLayoutManager = ensureScrollLayoutManager()
                scrollLayoutManager.onAttach(mAdapter)
                scrollLayoutManager.setCurrentIndex(mIndex)
                mAdapter = null
                mLayoutManager = scrollLayoutManager
            }
        }

        requestFill()
    }

    private fun detachLayoutManager() {
        val layoutManager = mLayoutManager ?: return

        mIndex = layoutManager.getInternalCurrentIndex()
        mAdapter = layoutManager.onDetach()
        mLayoutManager = null
    }

    // Internal for GalleryViewResizeTest (no GLRoot in a JVM test).
    @JvmName("onAttachToRootInternal")
    internal fun onAttachToRootInternal() {
        if (null == mPageTextTexture) {
            mPageTextTexture = ImageMovableTextTexture.create(
                mPageTextTypeface,
                mPageTextSize, mPageTextColor,
                charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9'),
            )
        }
        attachLayoutManager()
    }

    private fun setPagerIntervalInternal(interval: Int) {
        mPagerInterval = interval
        val pagerLayoutManager = mPagerLayoutManager
        if (pagerLayoutManager != null) {
            pagerLayoutManager.setInterval(interval)
        }
    }

    private fun setScrollIntervalInternal(interval: Int) {
        mScrollInterval = interval
        val scrollLayoutManager = mScrollLayoutManager
        if (scrollLayoutManager != null) {
            scrollLayoutManager.setInterval(interval)
        }
    }

    override fun onAttachToRoot(root: GLRoot) {
        super.onAttachToRoot(root)
        mEdgeView.onAttachToRoot(root)
        postMethod(METHOD_ON_ATTACH_TO_ROOT)
    }

    override fun onDetachFromRoot() {
        // When detached, render() will not be called. So do it here
        detachLayoutManager()
        val pageTextTexture = mPageTextTexture
        if (null != pageTextTexture) {
            pageTextTexture.recycle()
            mPageTextTexture = null
        }

        super.onDetachFromRoot()
        mEdgeView.onDetachFromRoot()
    }

    var layoutMode: Int
        get() = mLayoutMode
        set(@LayoutMode layoutMode) {
            postMethod(METHOD_SET_LAYOUT_MODE, layoutMode)
        }

    /**
     * Whether the reading position is at the very end of the gallery:
     * pager mode = the last page is the current page; scroll mode = the
     * strip is scrolled to (or past) the bottom of the last page.
     * Render-thread state; safe to call from the render thread.
     */
    val isReachEnd: Boolean
        get() {
            val layoutManager = mLayoutManager
            return layoutManager != null && layoutManager.isReachEnd()
        }

    override fun requestLayout() {
        // A child's layout request only needs a refill. A resize of this
        // view itself (rotation handled in place) refills from onLayout.
        requestFill()
    }

    fun requestFill() {
        if (mEnableRequestFill) {
            mRequestFill = true
            if (!mWillFill) {
                invalidate()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Do not pass event to component, so handle event here
        mGestureRecognizer.onTouchEvent(event)
        return true
    }

    @JvmName("getEdgeView")
    internal fun getEdgeView(): GLEdgeView = mEdgeView

    @JvmName("getDefaultErrorStr")
    internal fun getDefaultErrorStr(): String = mDefaultErrorString

    @JvmName("getEmptyStr")
    internal fun getEmptyStr(): String = mEmptyString

    @JvmName("isFirstScroll")
    internal fun isFirstScroll(): Boolean {
        val firstScroll = mFirstScroll
        mFirstScroll = false
        return firstScroll
    }

    // Make sure method run in render thread to ensure thread safe
    private fun postMethod(method: Int, vararg args: Any) {
        synchronized(this) {
            mMethodList.add(method)
            mArgsList.add(args)
        }

        invalidate()
    }

    fun setCurrentPage(page: Int) {
        postMethod(METHOD_SET_CURRENT_PAGE, page)
    }

    /**
     * Jump to [page] and queue an intra-page scroll restore at
     * [fraction] (0.0 ~ 1.0 of the target page's height) once that
     * page is laid out and loaded. Only meaningful in
     * `LAYOUT_TOP_TO_BOTTOM` — pager modes silently ignore the
     * fraction. Posts to the render thread.
     */
    fun setCurrentPageScrollFraction(page: Int, fraction: Float) {
        postMethod(METHOD_SET_CURRENT_PAGE_WITH_FRACTION, page, fraction)
    }

    /**
     * Latest intra-page scroll fraction, published from the render
     * thread after each fill. Safe to call from any thread; the
     * read is single-volatile-load.
     */
    val currentScrollFraction: Float
        get() = mCurrentScrollFraction

    fun pageLeft() {
        postMethod(METHOD_PAGE_LEFT)
    }

    fun pageRight() {
        postMethod(METHOD_PAGE_RIGHT)
    }

    fun setScaleMode(scaleMode: Int) {
        postMethod(METHOD_SET_SCALE_MODE, scaleMode)
    }

    fun setStartPosition(startPosition: Int) {
        postMethod(METHOD_SET_START_POSITION, startPosition)
    }

    fun setPagerInterval(interval: Int) {
        postMethod(METHOD_SET_PAGER_INTERVAL, interval)
    }

    fun setScrollInterval(interval: Int) {
        postMethod(METHOD_SET_SCROLL_INTERVAL, interval)
    }

    override fun onSingleTapUp(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_SINGLE_TAP_UP, x, y)
        return true
    }

    override fun onSingleTapConfirmed(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_SINGLE_TAP_CONFIRMED, x, y)
        return true
    }

    override fun onDoubleTap(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_DOUBLE_TAP, x, y)
        return true
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_DOUBLE_TAP_CONFIRMED, x, y)
        return true
    }

    override fun onLongPress(x: Float, y: Float) {
        val layoutManager = mLayoutManager
        if (layoutManager != null && !layoutManager.isTapOrPressEnable()) {
            return
        }

        postMethod(METHOD_ON_LONG_PRESS, x, y)
    }

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_SCROLL, dx, dy, totalX, totalY, x, y)
        return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        postMethod(METHOD_ON_FLING, velocityX, velocityY)
        return true
    }

    override fun onScaleBegin(focusX: Float, focusY: Float): Boolean {
        postMethod(METHOD_ON_SCALE_BEGIN, focusX, focusY)
        return true
    }

    override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
        postMethod(METHOD_ON_SCALE, focusX, focusY, scale)
        return true
    }

    override fun onScaleEnd() {
        postMethod(METHOD_ON_SCALE_END)
    }

    override fun onDown(x: Float, y: Float) {
        postMethod(METHOD_ON_DOWN, x, y)
    }

    override fun onUp() {
        postMethod(METHOD_ON_UP)
    }

    override fun onPointerDown(x: Float, y: Float) {
        postMethod(METHOD_ON_POINTER_DOWN, x, y)
    }

    override fun onPointerUp() {
        postMethod(METHOD_ON_POINTER_UP)
    }

    override fun onLayout(changeSize: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        mEdgeView.layout(left, top, right, bottom)

        // fill() is a no-op unless a fill was requested, and nothing else
        // requests one on a resize: the layout managers would keep every
        // page at the old width (a rotation handled in place left the
        // pages in their portrait geometry, audit PERF-02).
        if (changeSize) {
            mRequestFill = true
        }
        fill()

        if (changeSize) {
            val width = right - left
            val height = bottom - top
            mLeftArea.set(
                (LEFT_AREA[0] * width).toInt(), (LEFT_AREA[1] * height).toInt(),
                (LEFT_AREA[2] * width).toInt(), (LEFT_AREA[3] * height).toInt(),
            )
            mRightArea.set(
                (RIGHT_AREA[0] * width).toInt(), (RIGHT_AREA[1] * height).toInt(),
                (RIGHT_AREA[2] * width).toInt(), (RIGHT_AREA[3] * height).toInt(),
            )
            mMenuArea.set(
                (MENU_AREA[0] * width).toInt(), (MENU_AREA[1] * height).toInt(),
                (MENU_AREA[2] * width).toInt(), (MENU_AREA[3] * height).toInt(),
            )
            mSliderArea.set(
                (SLIDER_AREA[0] * width).toInt(), (SLIDER_AREA[1] * height).toInt(),
                (SLIDER_AREA[2] * width).toInt(), (SLIDER_AREA[3] * height).toInt(),
            )
        }
    }

    @RenderThread
    fun onDataChanged() {
        try {
            GalleryUtils.assertInRenderThread()
            val layoutManager = mLayoutManager
            if (layoutManager != null) {
                layoutManager.onDataChanged()
            }
        } catch (e: AssertionError) {
            Log.e(this.javaClass.name, e.message, e)
        }
    }

    private fun onSingleTapUpInternal(x: Float, y: Float) {
    }

    private fun findPageUnder(x: Float, y: Float): GalleryPageView? {
        for (i in 0 until componentCount) {
            val view = getComponent(i)
            if (view is GalleryPageView && view.bounds().contains(x.toInt(), y.toInt())) {
                return view
            }
        }
        return null
    }

    private fun onSingleTapConfirmedInternal(x: Float, y: Float) {
        val layoutManager = mLayoutManager
        if (layoutManager == null || !layoutManager.isTapOrPressEnable()) {
            return
        }

        Log.d(
            "GalleryView",
            "tap: x=" + x + " y=" + y +
                " menuArea=" + mMenuArea + " sliderArea=" + mSliderArea +
                " viewSize=" + width + "x" + height,
        )

        val page = findPageUnder(x, y)
        if (page != null &&
            page.index != GalleryPageView.INVALID_INDEX &&
            page.isError &&
            page.isUnderInfo(x - page.bounds().left, y - page.bounds().top)
        ) {
            if (mListener != null) {
                mListener.onTapErrorText(page.index)
            }
        } else if (mSliderArea.contains(x.toInt(), y.toInt())) {
            if (mListener != null) {
                mListener.onTapSliderArea()
            }
        } else if (mMenuArea.contains(x.toInt(), y.toInt())) {
            if (mListener != null) {
                mListener.onTapMenuArea()
            }
        } else if (mLeftArea.contains(x.toInt(), y.toInt())) {
            layoutManager.onPageLeft()
        } else if (mRightArea.contains(x.toInt(), y.toInt())) {
            layoutManager.onPageRight()
        }
    }

    private fun onDoubleTapInternal(x: Float, y: Float) {
    }

    private fun onDoubleTapConfirmedInternal(x: Float, y: Float) {
        if (mScale) {
            return
        }

        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onDoubleTapConfirmed(x, y)
        }
    }

    private fun onLongPressInternal(x: Float, y: Float) {
        if (mScale) {
            return
        }

        val layoutManager = mLayoutManager ?: return

        val index = layoutManager.getIndexUnder(x, y)
        if (index == GalleryPageView.INVALID_INDEX) {
            return
        }

        if (mListener != null) {
            mListener.onLongPressPage(index)
        }
    }

    private fun onScrollInternal(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        if (mScale) {
            return
        }
        mScroll = true

        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onScroll(dx, dy, totalX, totalY, x, y)
        }
    }

    private fun onFlingInternal(velocityX: Float, velocityY: Float) {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onFling(velocityX, velocityY)
        }
    }

    private fun onScaleBeginInternal(focusX: Float, focusY: Float) {
        onScaleInternal(focusX, focusY, 1.0f)
    }

    private fun onScaleInternal(focusX: Float, focusY: Float, scale: Float) {
        val layoutManager = mLayoutManager
        if (mScroll || (layoutManager != null && !layoutManager.canScale())) {
            return
        }
        mScale = true

        if (layoutManager != null) {
            layoutManager.onScale(focusX, focusY, scale)
        }
    }

    private fun onScaleEndInternal() {
    }

    private fun onDownInternal(x: Float, y: Float) {
        mScale = false
        mScroll = false
        mFirstScroll = true
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onDown()
        }
    }

    private fun onUpInternal() {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onUp()
        }
    }

    private fun onPointerDownInternal(x: Float, y: Float) {
        val layoutManager = mLayoutManager
        if (!mScroll && (layoutManager != null && layoutManager.canScale())) {
            mScale = true
        }
    }

    private fun onPointerUpInternal() {
    }

    private fun setLayoutModeInternal(layoutMode: Int) {
        if (mLayoutMode == layoutMode) {
            return
        }
        mLayoutMode = layoutMode

        val layoutManager = mLayoutManager ?: return

        when (mLayoutMode) {
            LAYOUT_LEFT_TO_RIGHT -> {
                val attached = mPagerLayoutManager
                if (attached != null && layoutManager === attached) {
                    // mPagerLayoutManager already attached, just change mode
                    attached.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                } else {
                    val pagerLayoutManager = ensurePagerLayoutManager()
                    pagerLayoutManager.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                    val index = layoutManager.getInternalCurrentIndex()
                    pagerLayoutManager.onAttach(layoutManager.onDetach())
                    pagerLayoutManager.setCurrentIndex(index)
                    mLayoutManager = pagerLayoutManager
                }
            }
            LAYOUT_RIGHT_TO_LEFT -> {
                val attached = mPagerLayoutManager
                if (attached != null && layoutManager === attached) {
                    // mPagerLayoutManager already attached, just change mode
                    attached.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                } else {
                    val pagerLayoutManager = ensurePagerLayoutManager()
                    pagerLayoutManager.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                    val index = layoutManager.getInternalCurrentIndex()
                    pagerLayoutManager.onAttach(layoutManager.onDetach())
                    pagerLayoutManager.setCurrentIndex(index)
                    mLayoutManager = pagerLayoutManager
                }
            }
            LAYOUT_TOP_TO_BOTTOM -> {
                val scrollLayoutManager = ensureScrollLayoutManager()
                val index = layoutManager.getInternalCurrentIndex()
                scrollLayoutManager.onAttach(layoutManager.onDetach())
                scrollLayoutManager.setCurrentIndex(index)
                mLayoutManager = scrollLayoutManager
            }
        }

        requestFill()
    }

    private fun setCurrentPageInternal(page: Int) {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.setCurrentIndex(page)
        } else {
            mIndex = page
        }
    }

    private fun setCurrentPageWithFractionInternal(page: Int, fraction: Float) {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.setCurrentIndex(page)
            layoutManager.setStartScrollFraction(page, fraction)
        } else {
            mIndex = page
            // No layout manager yet — fraction can't be applied. The
            // caller is responsible for invoking us again once a
            // layout manager exists (typically after onAttachToRoot).
        }
    }

    private fun pageLeftInternal() {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onPageLeft()
        }
    }

    private fun pageRightInternal() {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onPageRight()
        }
    }

    private fun setScaleModeInternal(scaleMode: Int) {
        mScaleMode = scaleMode
        val pagerLayoutManager = mPagerLayoutManager
        if (pagerLayoutManager != null) {
            pagerLayoutManager.setScaleMode(scaleMode)
        }
    }

    private fun setStartPositionInternal(startPosition: Int) {
        mStartPosition = startPosition
        val pagerLayoutManager = mPagerLayoutManager
        if (pagerLayoutManager != null) {
            pagerLayoutManager.setStartPosition(startPosition)
        }
    }

    @RenderThread
    @JvmName("forceFill")
    internal fun forceFill() {
        mRequestFill = true
        fill()
    }

    @RenderThread
    private fun fill() {
        GalleryUtils.assertInRenderThread()

        if (!mRequestFill) {
            return
        }

        // Disable request layout
        mEnableRequestFill = false
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            layoutManager.onFill()
        }
        mEnableRequestFill = true
        mRequestFill = false
    }

    // Java's (Float) / (Integer) casts of the posted arguments. The smart
    // cast after an is-check compiles to instanceof + checkcast + unboxing,
    // without the null check an as-cast adds for every argument.
    @Suppress("NOTHING_TO_INLINE")
    private inline fun Array<out Any>.floatAt(index: Int): Float {
        val value = this[index]
        if (value !is Float) throw ClassCastException()
        return value
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Array<out Any>.intAt(index: Int): Int {
        val value = this[index]
        if (value !is Int) throw ClassCastException()
        return value
    }

    private fun dispatchMethod() {
        val methodListTemp = mMethodListTemp
        val argsListTemp = mArgsListTemp

        synchronized(this) {
            if (mMethodList.isEmpty()) {
                return
            }

            methodListTemp.addAll(mMethodList)
            argsListTemp.addAll(mArgsList)
            mMethodList.clear()
            mArgsList.clear()
        }

        for (i in 0 until methodListTemp.size) {
            val method = methodListTemp[i]
            val args = argsListTemp[i]

            when (method) {
                METHOD_ON_SINGLE_TAP_UP -> onSingleTapUpInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SINGLE_TAP_CONFIRMED -> onSingleTapConfirmedInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_DOUBLE_TAP -> onDoubleTapInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_DOUBLE_TAP_CONFIRMED -> onDoubleTapConfirmedInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_LONG_PRESS -> onLongPressInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCROLL -> onScrollInternal(
                    args.floatAt(0), args.floatAt(1), args.floatAt(2),
                    args.floatAt(3), args.floatAt(4), args.floatAt(5),
                )
                METHOD_ON_FLING -> onFlingInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCALE_BEGIN -> onScaleBeginInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCALE -> onScaleInternal(args.floatAt(0), args.floatAt(1), args.floatAt(2))
                METHOD_ON_SCALE_END -> onScaleEndInternal()
                METHOD_ON_DOWN -> onDownInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_UP -> onUpInternal()
                METHOD_ON_POINTER_DOWN -> onPointerDownInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_POINTER_UP -> onPointerUpInternal()
                METHOD_SET_LAYOUT_MODE -> setLayoutModeInternal(args.intAt(0))
                METHOD_SET_CURRENT_PAGE -> setCurrentPageInternal(args.intAt(0))
                METHOD_SET_CURRENT_PAGE_WITH_FRACTION ->
                    setCurrentPageWithFractionInternal(args.intAt(0), args.floatAt(1))
                METHOD_PAGE_LEFT -> pageLeftInternal()
                METHOD_PAGE_RIGHT -> pageRightInternal()
                METHOD_SET_SCALE_MODE -> setScaleModeInternal(args.intAt(0))
                METHOD_SET_START_POSITION -> setStartPositionInternal(args.intAt(0))
                METHOD_ON_ATTACH_TO_ROOT -> onAttachToRootInternal()
                METHOD_SET_PAGER_INTERVAL -> setPagerIntervalInternal(args.intAt(0))
                METHOD_SET_SCROLL_INTERVAL -> setScrollIntervalInternal(args.intAt(0))
            }
        }

        methodListTemp.clear()
        argsListTemp.clear()
    }

    override fun render(canvas: GLCanvas) {
        mWillFill = true
        val oldCurrentIndex = mCurrentIndex

        // Dispatch method
        dispatchMethod()

        val time = AnimationTime.get()
        val animatingLayoutManager = mLayoutManager
        if (animatingLayoutManager != null && animatingLayoutManager.onUpdateAnimation(time)) {
            invalidate()
        }

        fill()
        mWillFill = false

        super.render(canvas)
        mEdgeView.render(canvas)

        val newCurrentIndex: Int
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            newCurrentIndex = layoutManager.getCurrentIndex()
            mCurrentScrollFraction = layoutManager.getCurrentScrollFraction()
        } else {
            newCurrentIndex = GalleryPageView.INVALID_INDEX
            mCurrentScrollFraction = 0f
        }
        mCurrentIndex = newCurrentIndex

        if (oldCurrentIndex != newCurrentIndex && mListener != null) {
            mListener.onUpdateCurrentIndex(newCurrentIndex)
        }

        updatePageTransforms()
    }

    /**
     * Main-thread-safe snapshot of the visible pages' image rects
     * (GalleryView-local coordinates). Updated after each render pass;
     * change notifications arrive via Listener.onPageTransformsChanged().
     */
    fun getPageTransforms(): List<PageTransform> = mPageTransforms

    /**
     * Enable/disable the per-frame page-transform collection. Its only
     * consumer is the reader-stamps overlay (hidden by default); while the
     * overlay is invisible the per-visible-page allocations and the
     * main-thread notify per geometry change are pure waste on the render
     * hot path. Enabling requests a render so a fresh snapshot lands
     * without waiting for the next user scroll. Callable from any thread.
     */
    fun setPageTransformCollectionEnabled(enabled: Boolean) {
        if (mPageTransformCollectionEnabled == enabled) {
            return
        }
        mPageTransformCollectionEnabled = enabled
        if (enabled) {
            invalidate()
        }
    }

    @RenderThread
    private fun updatePageTransforms() {
        if (!mPageTransformCollectionEnabled) {
            return
        }
        val layoutManager = mLayoutManager
        mTransformScratch.clear()
        if (layoutManager != null) {
            layoutManager.collectPageTransforms(mTransformScratch)
        }
        // List.equals directly, as Java did (no Intrinsics.areEqual step).
        if (mTransformScratch.equals(mPageTransforms)) {
            return
        }
        mPageTransforms = Collections.unmodifiableList(ArrayList(mTransformScratch))
        val listener = mListener
        if (listener != null) {
            listener.onPageTransformsChanged()
        }
    }

    @RenderThread
    fun findPageByIndex(id: Int): GalleryPageView? {
        val layoutManager = mLayoutManager
        if (layoutManager != null) {
            return layoutManager.findPageByIndex(id)
        } else {
            return null
        }
    }

    @JvmName("obtainPage")
    internal fun obtainPage(): GalleryPageView {
        var page = mGalleryPageViewPool.acquire()
        if (page == null) {
            page = GalleryPageView(
                mPageTextTexture,
                mProgressColor, mBackgroundColor, mProgressSize,
                mPageMinHeight, mPageInfoInterval,
            )
        }
        return page
    }

    @JvmName("releasePage")
    internal fun releasePage(page: GalleryPageView) {
        mGalleryPageViewPool.release(page)
    }

    /**
     * Indeterminate GLProgressView
     */
    @JvmName("obtainProgress")
    internal fun obtainProgress(): GLProgressView {
        val progress: GLProgressView
        val progressCache = mProgressCache
        if (progressCache != null) {
            progress = progressCache
            mProgressCache = null
        } else {
            progress = GLProgressView()
            progress.setColor(mProgressColor)
            progress.setBgColor(mBackgroundColor)
            progress.isIndeterminate = true
            progress.minimumWidth = mProgressSize
            progress.minimumHeight = mProgressSize
        }
        return progress
    }

    /**
     * @param progress Indeterminate GLProgressView
     */
    @JvmName("releaseProgress")
    internal fun releaseProgress(progress: GLProgressView) {
        mProgressCache = progress
    }

    @JvmName("obtainErrorView")
    internal fun obtainErrorView(): GLTextureView {
        val errorView: GLTextureView
        val errorViewCache = mErrorViewCache
        if (errorViewCache != null) {
            errorView = errorViewCache
            mErrorViewCache = null
        } else {
            errorView = GLTextureView()
        }
        return errorView
    }

    @JvmName("unbindErrorView")
    internal fun unbindErrorView(errorView: GLTextureView) {
        val texture = errorView.texture
        if (texture != null) {
            errorView.texture = null
            if (texture is BasicTexture) {
                texture.recycle()
            }
        }
    }

    @JvmName("bindErrorView")
    internal fun bindErrorView(errorView: GLTextureView, error: String) {
        unbindErrorView(errorView)

        val texture = StringTexture.newInstance(error, mErrorTextSize.toFloat(), mErrorTextColor)
        errorView.texture = texture
    }

    @JvmName("releaseErrorView")
    internal fun releaseErrorView(errorView: GLTextureView) {
        unbindErrorView(errorView)
        mErrorViewCache = errorView
    }

    fun onTransferEnd() {
        if (mListener == null) {
            return
        }
        mListener.onAutoTransferDone()
    }

    abstract class Adapter {

        @JvmField
        protected var mGalleryView: GalleryView? = null

        @JvmName("setGalleryView")
        internal fun setGalleryView(galleryView: GalleryView) {
            mGalleryView = galleryView
        }

        fun bind(view: GalleryPageView, index: Int) {
            onBind(view, index)
            view.index = index
        }

        fun unbind(view: GalleryPageView) {
            onUnbind(view, view.index)
            view.index = GalleryPageView.INVALID_INDEX
        }

        abstract fun onBind(view: GalleryPageView, index: Int)

        abstract fun onUnbind(view: GalleryPageView, index: Int)

        /**
         * @return Null for no error
         */
        abstract fun getError(): String?

        abstract fun size(): Int
    }

    abstract class LayoutManager(@JvmField protected val mGalleryView: GalleryView) {

        abstract fun onAttach(adapter: Adapter?)

        abstract fun onDetach(): Adapter?

        abstract fun onFill()

        abstract fun onDown()

        abstract fun onUp()

        abstract fun onDoubleTapConfirmed(x: Float, y: Float)

        abstract fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float)

        abstract fun onFling(velocityX: Float, velocityY: Float)

        abstract fun canScale(): Boolean

        abstract fun onScale(focusX: Float, focusY: Float, scale: Float)

        abstract fun onUpdateAnimation(time: Long): Boolean

        abstract fun onDataChanged()

        abstract fun onPageLeft()

        abstract fun onPageRight()

        /**
         * Whether the reading position is at the very end of the gallery.
         * Pager mode: the current page is the last page. Scroll mode: the
         * strip is scrolled to (or past) the bottom of the last page.
         * Called on the render thread.
         */
        abstract fun isReachEnd(): Boolean

        /**
         * Collects current visible pages' image screen rects. Called on the
         * render thread after each render pass; implementations add one
         * PageTransform per attached page whose image is loaded.
         */
        abstract fun collectPageTransforms(out: MutableList<PageTransform>)

        abstract fun isTapOrPressEnable(): Boolean

        abstract fun findPageByIndex(index: Int): GalleryPageView?

        /**
         * @return [GalleryPageView.INVALID_INDEX] for error
         */
        abstract fun getCurrentIndex(): Int

        abstract fun setCurrentIndex(index: Int)

        abstract fun getIndexUnder(x: Float, y: Float): Int

        internal abstract fun getInternalCurrentIndex(): Int

        /**
         * Set a pending intra-page scroll fraction (0.0 ~ 1.0) on
         * the given target page, to apply on the next successful
         * fill where that page is laid out and loaded. The page
         * index is passed explicitly because the layout manager's
         * internal mIndex can drift away from the caller's intent
         * (fillPages sets mIndex to the first page in mPages, which
         * for backward-extended layouts is not the requested page).
         * Default no-op for layout managers that don't support
         * intra-page scrolling (e.g. pager mode).
         */
        open fun setStartScrollFraction(pageIndex: Int, fraction: Float) {
            // No-op by default
        }

        /**
         * Current intra-page scroll position as a fraction (0.0 ~
         * 1.0) of the visible page's height. Default 0 for layout
         * managers without continuous scroll.
         */
        open fun getCurrentScrollFraction(): Float = 0f

        protected fun placeCenter(view: GLView) {
            val spec = GLView.MeasureSpec.makeMeasureSpec(
                GLView.LayoutParams.WRAP_CONTENT,
                GLView.LayoutParams.WRAP_CONTENT,
            )
            view.measure(spec, spec)
            val viewWidth = view.measuredWidth
            val viewHeight = view.measuredHeight
            val viewLeft = mGalleryView.width / 2 - viewWidth / 2
            val viewTop = mGalleryView.height / 2 - viewHeight / 2
            view.layout(viewLeft, viewTop, viewLeft + viewWidth, viewTop + viewHeight)
        }

        protected fun collectPageTransform(out: MutableList<PageTransform>, page: GalleryPageView?) {
            if (page == null) {
                return
            }
            val image = page.imageView
            val dst = RectF()
            if (!image.getImageRect(dst)) {
                return
            }
            // Enforced here so a future bind-order refactor can't leak index=-1 into consumers.
            val index = page.index
            if (index == GalleryPageView.INVALID_INDEX) {
                return
            }
            val imageBounds = Rect()
            if (!mGalleryView.getBoundsOf(image, imageBounds)) {
                return
            }
            dst.offset(imageBounds.left.toFloat(), imageBounds.top.toFloat())
            out.add(PageTransform(index, dst))
        }
    }

    interface Listener {

        @RenderThread
        fun onUpdateCurrentIndex(index: Int)

        @RenderThread
        fun onTapSliderArea()

        @RenderThread
        fun onTapMenuArea()

        @RenderThread
        fun onTapErrorText(index: Int)

        @RenderThread
        fun onLongPressPage(index: Int)

        /**
         * During gesture/fling animation this fires per rendered frame (the
         * dedup only suppresses static geometry) — consumers must stay cheap
         * and read the latest snapshot rather than doing per-event work.
         */
        @RenderThread
        fun onPageTransformsChanged()

        fun onAutoTransferDone()
    }

    companion object {
        const val LAYOUT_LEFT_TO_RIGHT = 0
        const val LAYOUT_RIGHT_TO_LEFT = 1
        const val LAYOUT_TOP_TO_BOTTOM = 2

        const val SCALE_ORIGIN = ImageView.SCALE_ORIGIN
        const val SCALE_FIT_WIDTH = ImageView.SCALE_FIT_WIDTH
        const val SCALE_FIT_HEIGHT = ImageView.SCALE_FIT_HEIGHT
        const val SCALE_FIT = ImageView.SCALE_FIT
        const val SCALE_FIXED = ImageView.SCALE_FIXED

        const val START_POSITION_TOP_LEFT = ImageView.START_POSITION_TOP_LEFT
        const val START_POSITION_TOP_RIGHT = ImageView.START_POSITION_TOP_RIGHT
        const val START_POSITION_BOTTOM_LEFT = ImageView.START_POSITION_BOTTOM_LEFT
        const val START_POSITION_BOTTOM_RIGHT = ImageView.START_POSITION_BOTTOM_RIGHT
        const val START_POSITION_CENTER = ImageView.START_POSITION_CENTER

        private val LEFT_AREA = floatArrayOf(0.0f, 0.0f, 1.0f / 3.0f, 1f)
        private val RIGHT_AREA = floatArrayOf(2.0f / 3.0f, 0.0f, 1.0f, 1f)
        private val MENU_AREA = floatArrayOf(1.0f / 3.0f, 0.0f, 2.0f / 3.0f, 1.0f / 2.0f)
        private val SLIDER_AREA = floatArrayOf(1.0f / 3.0f, 1.0f / 2.0f, 2.0f / 3.0f, 1.0f)

        private const val METHOD_ON_SINGLE_TAP_UP = 0
        private const val METHOD_ON_SINGLE_TAP_CONFIRMED = 1
        private const val METHOD_ON_DOUBLE_TAP = 2
        private const val METHOD_ON_DOUBLE_TAP_CONFIRMED = 3
        private const val METHOD_ON_LONG_PRESS = 4
        private const val METHOD_ON_SCROLL = 5
        private const val METHOD_ON_FLING = 6
        private const val METHOD_ON_SCALE_BEGIN = 7
        private const val METHOD_ON_SCALE = 8
        private const val METHOD_ON_SCALE_END = 9
        private const val METHOD_ON_DOWN = 10
        private const val METHOD_ON_UP = 11
        private const val METHOD_ON_POINTER_DOWN = 12
        private const val METHOD_ON_POINTER_UP = 13
        private const val METHOD_SET_LAYOUT_MODE = 14
        private const val METHOD_SET_CURRENT_PAGE = 15
        private const val METHOD_PAGE_LEFT = 16
        private const val METHOD_PAGE_RIGHT = 17
        private const val METHOD_SET_SCALE_MODE = 18
        private const val METHOD_SET_START_POSITION = 19
        private const val METHOD_ON_ATTACH_TO_ROOT = 20
        private const val METHOD_SET_PAGER_INTERVAL = 21
        private const val METHOD_SET_SCROLL_INTERVAL = 22
        private const val METHOD_SET_CURRENT_PAGE_WITH_FRACTION = 23

        @LayoutMode
        @JvmStatic
        fun sanitizeLayoutMode(layoutMode: Int): Int {
            if (layoutMode != LAYOUT_LEFT_TO_RIGHT &&
                layoutMode != LAYOUT_RIGHT_TO_LEFT &&
                layoutMode != LAYOUT_TOP_TO_BOTTOM
            ) {
                return LAYOUT_LEFT_TO_RIGHT
            } else {
                return layoutMode
            }
        }

        @ScaleMode
        @JvmStatic
        fun sanitizeScaleMode(scaleMode: Int): Int {
            if (scaleMode != SCALE_ORIGIN &&
                scaleMode != SCALE_FIT_WIDTH &&
                scaleMode != SCALE_FIT_HEIGHT &&
                scaleMode != SCALE_FIT &&
                scaleMode != SCALE_FIXED
            ) {
                return SCALE_FIT
            } else {
                return scaleMode
            }
        }

        @StartPosition
        @JvmStatic
        fun sanitizeStartPosition(startPosition: Int): Int {
            if (startPosition != START_POSITION_TOP_LEFT &&
                startPosition != START_POSITION_TOP_RIGHT &&
                startPosition != START_POSITION_BOTTOM_LEFT &&
                startPosition != START_POSITION_BOTTOM_RIGHT &&
                startPosition != START_POSITION_CENTER
            ) {
                return START_POSITION_TOP_LEFT
            } else {
                return startPosition
            }
        }
    }
}
