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

@Suppress("LargeClass") // the gesture queue, fill gate and layout-manager switch share one render-thread state
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

    private val context: Context = build.context
    private var adapter: Adapter? = build.adapter
    private val gestureRecognizer: GestureRecognizer
    private val listener: Listener? = build.listener

    private var pageTextTexture: ImageMovableTextTexture? = null

    private var pagerLayoutManager: PagerLayoutManager? = null
    private var scrollLayoutManager: ScrollLayoutManager? = null
    private var layoutManager: LayoutManager? = null

    private val transformScratch: MutableList<PageTransform> = ArrayList()

    /**
     * Main-thread-safe snapshot of the visible pages' image rects
     * (GalleryView-local coordinates). Updated after each render pass;
     * change notifications arrive via Listener.onPageTransformsChanged().
     */
    @Volatile
    var pageTransforms: List<PageTransform> = Collections.emptyList()
        private set

    // Off by default: matches the stamps overlay's hidden default; see
    // setPageTransformCollectionEnabled(boolean).
    @Volatile
    private var pageTransformCollectionEnabled = false

    @get:JvmName("getEdgeView")
    internal val edgeView: GLEdgeView
    private val galleryPageViewPool = Pools.SimplePool<GalleryPageView>(5)
    private var progressCache: GLProgressView? = null
    private var errorViewCache: GLTextureView? = null

    private val pageBackgroundColor: Int = build.backgroundColor
    private var pagerInterval: Int = build.pagerInterval
    private var scrollInterval: Int = build.scrollInterval
    private val pageMinHeight: Int = build.pageMinHeight
    private val pageInfoInterval: Int = build.pageInfoInterval
    private val progressColor: Int = build.progressColor
    private val progressSize: Int = build.progressSize
    private val pageTextColor: Int = build.pageTextColor
    private val pageTextSize: Int = build.pageTextSize
    private val pageTextTypeface: Typeface = build.pageTextTypeface
    private val errorTextSize: Int = build.errorTextSize
    private val errorTextColor: Int = build.errorTextColor

    @get:JvmName("getDefaultErrorStr")
    internal val defaultErrorStr: String = build.defaultErrorString

    @get:JvmName("getEmptyStr")
    internal val emptyStr: String = build.emptyString

    private var requestFillEnabled = true
    private var fillRequested = false
    private var willFill = false

    private var scaling = false
    private var scrolling = false
    private var firstScroll = false

    private val leftArea = Rect()
    private val rightArea = Rect()
    private val menuArea = Rect()
    private val sliderArea = Rect()

    private var currentLayoutMode: Int = build.layoutMode
    private var scaleMode: Int = build.scaleMode
    private var startPosition: Int = build.startPosition
    private var index: Int = MathUtils.clamp(build.startPage, 0, Int.MAX_VALUE)

    private val methodList: MutableList<Int> = ArrayList(5)
    private val argsList: MutableList<Array<out Any>> = ArrayList(5)
    private val methodListTemp: MutableList<Int> = ArrayList(5)
    private val argsListTemp: MutableList<Array<out Any>> = ArrayList(5)

    // Render-thread only: the last index reported to onUpdateCurrentIndex.
    private var currentIndex = GalleryPageView.INVALID_INDEX

    /**
     * Latest intra-page scroll fraction, published from the render
     * thread after each fill. Read by UI-thread callers to persist a
     * precise resume point on exit; safe to read from any thread (a
     * single volatile load). Always 0 for pager modes.
     */
    @Volatile
    var currentScrollFraction = 0f
        private set

    init {
        build.adapter.setGalleryView(this)
        gestureRecognizer = GestureRecognizer(context, this)
        edgeView = GLEdgeView(build.edgeColor)

        backgroundColor = pageBackgroundColor
    }

    class Builder(
        @JvmField internal val context: Context,
        @JvmField internal val adapter: Adapter,
    ) {

        @JvmField internal var listener: Listener? = null

        @JvmField internal var layoutMode = LAYOUT_LEFT_TO_RIGHT

        @JvmField internal var scaleMode = SCALE_FIT

        @JvmField internal var startPosition = START_POSITION_TOP_LEFT

        @JvmField internal var startPage = 0

        @JvmField internal var backgroundColor = Color.BLACK

        @JvmField internal var edgeColor = Color.WHITE

        @JvmField internal var pagerInterval = 48

        @JvmField internal var scrollInterval = 24

        @JvmField internal var pageMinHeight = 256

        @JvmField internal var pageInfoInterval = 24

        @JvmField internal var progressColor = Color.WHITE

        @JvmField internal var progressSize = 56

        @JvmField internal var pageTextColor = Color.WHITE

        @JvmField internal var pageTextSize = 56

        @JvmField internal var pageTextTypeface: Typeface = Typeface.DEFAULT

        @JvmField internal var errorTextColor = Color.RED

        @JvmField internal var errorTextSize = 24

        @JvmField internal var defaultErrorString = "Error"

        @JvmField internal var emptyString = "Empty"

        fun setListener(listener: Listener?): Builder {
            this.listener = listener
            return this
        }

        fun setLayoutMode(@LayoutMode layoutMode: Int): Builder {
            this.layoutMode = layoutMode
            return this
        }

        fun setScaleMode(@ScaleMode scaleMode: Int): Builder {
            this.scaleMode = scaleMode
            return this
        }

        fun setStartPosition(@StartPosition startPosition: Int): Builder {
            this.startPosition = startPosition
            return this
        }

        fun setStartPage(startPage: Int): Builder {
            this.startPage = startPage
            return this
        }

        fun setBackgroundColor(backgroundColor: Int): Builder {
            this.backgroundColor = backgroundColor
            return this
        }

        fun setEdgeColor(edgeColor: Int): Builder {
            this.edgeColor = edgeColor
            return this
        }

        fun setPagerInterval(pagerInterval: Int): Builder {
            this.pagerInterval = pagerInterval
            return this
        }

        fun setScrollInterval(scrollInterval: Int): Builder {
            this.scrollInterval = scrollInterval
            return this
        }

        fun setPageMinHeight(pageMinHeight: Int): Builder {
            this.pageMinHeight = pageMinHeight
            return this
        }

        fun setPageInfoInterval(pageInfoInterval: Int): Builder {
            this.pageInfoInterval = pageInfoInterval
            return this
        }

        fun setProgressColor(progressColor: Int): Builder {
            this.progressColor = progressColor
            return this
        }

        fun setProgressSize(progressSize: Int): Builder {
            this.progressSize = progressSize
            return this
        }

        fun setPageTextColor(pageTextColor: Int): Builder {
            this.pageTextColor = pageTextColor
            return this
        }

        fun setPageTextSize(pageTextSize: Int): Builder {
            this.pageTextSize = pageTextSize
            return this
        }

        fun setPageTextTypeface(pageTextTypeface: Typeface): Builder {
            this.pageTextTypeface = pageTextTypeface
            return this
        }

        fun setErrorTextColor(errorTextColor: Int): Builder {
            this.errorTextColor = errorTextColor
            return this
        }

        fun setErrorTextSize(errorTextSize: Int): Builder {
            this.errorTextSize = errorTextSize
            return this
        }

        fun setDefaultErrorString(defaultErrorString: String): Builder {
            this.defaultErrorString = defaultErrorString
            return this
        }

        fun setEmptyString(emptyString: String): Builder {
            this.emptyString = emptyString
            return this
        }

        fun build(): GalleryView = GalleryView(this)
    }

    private fun ensurePagerLayoutManager(): PagerLayoutManager {
        var pagerLayoutManager = this.pagerLayoutManager
        if (pagerLayoutManager == null) {
            pagerLayoutManager = PagerLayoutManager(
                context, this,
                scaleMode, startPosition, 1.0f, pagerInterval,
            )
            this.pagerLayoutManager = pagerLayoutManager
        }
        return pagerLayoutManager
    }

    private fun ensureScrollLayoutManager(): ScrollLayoutManager {
        var scrollLayoutManager = this.scrollLayoutManager
        if (scrollLayoutManager == null) {
            scrollLayoutManager = ScrollLayoutManager(context, this, scrollInterval)
            this.scrollLayoutManager = scrollLayoutManager
        }
        return scrollLayoutManager
    }

    private fun attachLayoutManager() {
        if (null != layoutManager) {
            return
        }
        // Without a layout manager, GalleryView holds the adapter.
        val adapter = this.adapter ?: throw AssertionError("The adapter is null")

        when (currentLayoutMode) {
            LAYOUT_LEFT_TO_RIGHT -> {
                val pagerLayoutManager = ensurePagerLayoutManager()
                pagerLayoutManager.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                pagerLayoutManager.onAttach(adapter)
                pagerLayoutManager.setCurrentIndex(index)
                this.adapter = null
                layoutManager = pagerLayoutManager
            }
            LAYOUT_RIGHT_TO_LEFT -> {
                val pagerLayoutManager = ensurePagerLayoutManager()
                pagerLayoutManager.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                pagerLayoutManager.onAttach(adapter)
                pagerLayoutManager.setCurrentIndex(index)
                this.adapter = null
                layoutManager = pagerLayoutManager
            }
            LAYOUT_TOP_TO_BOTTOM -> {
                val scrollLayoutManager = ensureScrollLayoutManager()
                scrollLayoutManager.onAttach(adapter)
                scrollLayoutManager.setCurrentIndex(index)
                this.adapter = null
                layoutManager = scrollLayoutManager
            }
        }

        requestFill()
    }

    private fun detachLayoutManager() {
        val layoutManager = this.layoutManager ?: return

        index = layoutManager.internalCurrentIndex
        adapter = layoutManager.onDetach()
        this.layoutManager = null
    }

    // Internal for GalleryViewResizeTest (no GLRoot in a JVM test).
    @JvmName("onAttachToRootInternal")
    internal fun onAttachToRootInternal() {
        if (null == pageTextTexture) {
            pageTextTexture = ImageMovableTextTexture.create(
                pageTextTypeface,
                pageTextSize, pageTextColor,
                charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9'),
            )
        }
        attachLayoutManager()
    }

    private fun setPagerIntervalInternal(interval: Int) {
        pagerInterval = interval
        pagerLayoutManager?.setInterval(interval)
    }

    private fun setScrollIntervalInternal(interval: Int) {
        scrollInterval = interval
        scrollLayoutManager?.setInterval(interval)
    }

    override fun onAttachToRoot(root: GLRoot) {
        super.onAttachToRoot(root)
        edgeView.onAttachToRoot(root)
        postMethod(METHOD_ON_ATTACH_TO_ROOT)
    }

    override fun onDetachFromRoot() {
        // When detached, render() will not be called. So do it here
        detachLayoutManager()
        val pageTextTexture = this.pageTextTexture
        if (null != pageTextTexture) {
            pageTextTexture.recycle()
            this.pageTextTexture = null
        }

        super.onDetachFromRoot()
        edgeView.onDetachFromRoot()
    }

    var layoutMode: Int
        get() = currentLayoutMode
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
            val layoutManager = this.layoutManager
            return layoutManager != null && layoutManager.isReachEnd
        }

    override fun requestLayout() {
        // A child's layout request only needs a refill. A resize of this
        // view itself (rotation handled in place) refills from onLayout.
        requestFill()
    }

    fun requestFill() {
        if (requestFillEnabled) {
            fillRequested = true
            if (!willFill) {
                invalidate()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Do not pass event to component, so handle event here
        gestureRecognizer.onTouchEvent(event)
        return true
    }

    @JvmName("isFirstScroll")
    internal fun isFirstScroll(): Boolean {
        val firstScroll = this.firstScroll
        this.firstScroll = false
        return firstScroll
    }

    // Make sure method run in render thread to ensure thread safe
    private fun postMethod(method: Int, vararg args: Any) {
        synchronized(this) {
            methodList.add(method)
            argsList.add(args)
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
        postMethod(METHOD_ON_SINGLE_TAP_UP)
        return true
    }

    override fun onSingleTapConfirmed(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_SINGLE_TAP_CONFIRMED, x, y)
        return true
    }

    override fun onDoubleTap(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_DOUBLE_TAP)
        return true
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float): Boolean {
        postMethod(METHOD_ON_DOUBLE_TAP_CONFIRMED, x, y)
        return true
    }

    override fun onLongPress(x: Float, y: Float) {
        val layoutManager = this.layoutManager
        if (layoutManager != null && !layoutManager.isTapOrPressEnable) {
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
        postMethod(METHOD_ON_DOWN)
    }

    override fun onUp() {
        postMethod(METHOD_ON_UP)
    }

    override fun onPointerDown(x: Float, y: Float) {
        postMethod(METHOD_ON_POINTER_DOWN)
    }

    override fun onPointerUp() {
        postMethod(METHOD_ON_POINTER_UP)
    }

    override fun onLayout(changeSize: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        edgeView.layout(left, top, right, bottom)

        // fill() is a no-op unless a fill was requested, and nothing else
        // requests one on a resize: the layout managers would keep every
        // page at the old width (a rotation handled in place left the
        // pages in their portrait geometry, audit PERF-02).
        if (changeSize) {
            fillRequested = true
        }
        fill()

        if (changeSize) {
            val width = right - left
            val height = bottom - top
            leftArea.set(
                (LEFT_AREA[0] * width).toInt(), (LEFT_AREA[1] * height).toInt(),
                (LEFT_AREA[2] * width).toInt(), (LEFT_AREA[3] * height).toInt(),
            )
            rightArea.set(
                (RIGHT_AREA[0] * width).toInt(), (RIGHT_AREA[1] * height).toInt(),
                (RIGHT_AREA[2] * width).toInt(), (RIGHT_AREA[3] * height).toInt(),
            )
            menuArea.set(
                (MENU_AREA[0] * width).toInt(), (MENU_AREA[1] * height).toInt(),
                (MENU_AREA[2] * width).toInt(), (MENU_AREA[3] * height).toInt(),
            )
            sliderArea.set(
                (SLIDER_AREA[0] * width).toInt(), (SLIDER_AREA[1] * height).toInt(),
                (SLIDER_AREA[2] * width).toInt(), (SLIDER_AREA[3] * height).toInt(),
            )
        }
    }

    @RenderThread
    fun onDataChanged() {
        try {
            GalleryUtils.assertInRenderThread()
            layoutManager?.onDataChanged()
        } catch (e: AssertionError) {
            Log.e(this.javaClass.name, e.message, e)
        }
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

    private fun isErrorTextTap(page: GalleryPageView, x: Float, y: Float): Boolean =
        page.index != GalleryPageView.INVALID_INDEX &&
            page.isError &&
            page.isUnderInfo(x - page.bounds().left, y - page.bounds().top)

    private fun onSingleTapConfirmedInternal(x: Float, y: Float) {
        val layoutManager = this.layoutManager
        if (layoutManager == null || !layoutManager.isTapOrPressEnable) {
            return
        }

        Log.d(
            "GalleryView",
            "tap: x=" + x + " y=" + y +
                " menuArea=" + menuArea + " sliderArea=" + sliderArea +
                " viewSize=" + width + "x" + height,
        )

        val page = findPageUnder(x, y)
        if (page != null && isErrorTextTap(page, x, y)) {
            listener?.onTapErrorText(page.index)
        } else if (sliderArea.contains(x.toInt(), y.toInt())) {
            listener?.onTapSliderArea()
        } else if (menuArea.contains(x.toInt(), y.toInt())) {
            listener?.onTapMenuArea()
        } else if (leftArea.contains(x.toInt(), y.toInt())) {
            layoutManager.onPageLeft()
        } else if (rightArea.contains(x.toInt(), y.toInt())) {
            layoutManager.onPageRight()
        }
    }

    private fun onDoubleTapConfirmedInternal(x: Float, y: Float) {
        if (scaling) {
            return
        }

        layoutManager?.onDoubleTapConfirmed(x, y)
    }

    private fun onLongPressInternal(x: Float, y: Float) {
        if (scaling) {
            return
        }

        val layoutManager = this.layoutManager ?: return

        val index = layoutManager.getIndexUnder(x, y)
        if (index == GalleryPageView.INVALID_INDEX) {
            return
        }

        listener?.onLongPressPage(index)
    }

    private fun onScrollInternal(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        if (scaling) {
            return
        }
        scrolling = true

        layoutManager?.onScroll(dx, dy, totalX, totalY, x, y)
    }

    private fun onFlingInternal(velocityX: Float, velocityY: Float) {
        layoutManager?.onFling(velocityX, velocityY)
    }

    private fun onScaleBeginInternal(focusX: Float, focusY: Float) {
        onScaleInternal(focusX, focusY, 1.0f)
    }

    private fun onScaleInternal(focusX: Float, focusY: Float, scale: Float) {
        val layoutManager = this.layoutManager
        if (scrolling || (layoutManager != null && !layoutManager.canScale())) {
            return
        }
        scaling = true

        if (layoutManager != null) {
            layoutManager.onScale(focusX, focusY, scale)
        }
    }

    private fun onDownInternal() {
        scaling = false
        scrolling = false
        firstScroll = true
        layoutManager?.onDown()
    }

    private fun onUpInternal() {
        layoutManager?.onUp()
    }

    private fun onPointerDownInternal() {
        val layoutManager = this.layoutManager
        if (!scrolling && (layoutManager != null && layoutManager.canScale())) {
            scaling = true
        }
    }

    private fun setLayoutModeInternal(layoutMode: Int) {
        if (currentLayoutMode == layoutMode) {
            return
        }
        currentLayoutMode = layoutMode

        val layoutManager = this.layoutManager ?: return

        when (currentLayoutMode) {
            LAYOUT_LEFT_TO_RIGHT -> {
                val attached = this.pagerLayoutManager
                if (attached != null && layoutManager === attached) {
                    // pagerLayoutManager already attached, just change mode
                    attached.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                } else {
                    val pagerLayoutManager = ensurePagerLayoutManager()
                    pagerLayoutManager.setMode(PagerLayoutManager.MODE_LEFT_TO_RIGHT)
                    val index = layoutManager.internalCurrentIndex
                    pagerLayoutManager.onAttach(layoutManager.onDetach())
                    pagerLayoutManager.setCurrentIndex(index)
                    this.layoutManager = pagerLayoutManager
                }
            }
            LAYOUT_RIGHT_TO_LEFT -> {
                val attached = this.pagerLayoutManager
                if (attached != null && layoutManager === attached) {
                    // pagerLayoutManager already attached, just change mode
                    attached.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                } else {
                    val pagerLayoutManager = ensurePagerLayoutManager()
                    pagerLayoutManager.setMode(PagerLayoutManager.MODE_RIGHT_TO_LEFT)
                    val index = layoutManager.internalCurrentIndex
                    pagerLayoutManager.onAttach(layoutManager.onDetach())
                    pagerLayoutManager.setCurrentIndex(index)
                    this.layoutManager = pagerLayoutManager
                }
            }
            LAYOUT_TOP_TO_BOTTOM -> {
                val scrollLayoutManager = ensureScrollLayoutManager()
                val index = layoutManager.internalCurrentIndex
                scrollLayoutManager.onAttach(layoutManager.onDetach())
                scrollLayoutManager.setCurrentIndex(index)
                this.layoutManager = scrollLayoutManager
            }
        }

        requestFill()
    }

    private fun setCurrentPageInternal(page: Int) {
        val layoutManager = this.layoutManager
        if (layoutManager != null) {
            layoutManager.setCurrentIndex(page)
        } else {
            index = page
        }
    }

    private fun setCurrentPageWithFractionInternal(page: Int, fraction: Float) {
        val layoutManager = this.layoutManager
        if (layoutManager != null) {
            layoutManager.setCurrentIndex(page)
            layoutManager.setStartScrollFraction(page, fraction)
        } else {
            index = page
            // No layout manager yet — fraction can't be applied. The
            // caller is responsible for invoking us again once a
            // layout manager exists (typically after onAttachToRoot).
        }
    }

    private fun pageLeftInternal() {
        layoutManager?.onPageLeft()
    }

    private fun pageRightInternal() {
        layoutManager?.onPageRight()
    }

    private fun setScaleModeInternal(scaleMode: Int) {
        this.scaleMode = scaleMode
        pagerLayoutManager?.setScaleMode(scaleMode)
    }

    private fun setStartPositionInternal(startPosition: Int) {
        this.startPosition = startPosition
        pagerLayoutManager?.setStartPosition(startPosition)
    }

    @RenderThread
    @JvmName("forceFill")
    internal fun forceFill() {
        fillRequested = true
        fill()
    }

    @RenderThread
    private fun fill() {
        GalleryUtils.assertInRenderThread()

        if (!fillRequested) {
            return
        }

        // Disable request layout
        requestFillEnabled = false
        layoutManager?.onFill()
        requestFillEnabled = true
        fillRequested = false
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

    @Suppress("CyclomaticComplexMethod") // one branch per posted method id; a table would need a lambda each
    private fun dispatchMethod() {
        val methodListTemp = this.methodListTemp
        val argsListTemp = this.argsListTemp

        synchronized(this) {
            if (methodList.isEmpty()) {
                return
            }

            methodListTemp.addAll(methodList)
            argsListTemp.addAll(argsList)
            methodList.clear()
            argsList.clear()
        }

        for (i in 0 until methodListTemp.size) {
            val method = methodListTemp[i]
            val args = argsListTemp[i]

            when (method) {
                METHOD_ON_SINGLE_TAP_CONFIRMED -> onSingleTapConfirmedInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_DOUBLE_TAP_CONFIRMED -> onDoubleTapConfirmedInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_LONG_PRESS -> onLongPressInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCROLL -> onScrollInternal(
                    args.floatAt(0), args.floatAt(1), args.floatAt(2),
                    args.floatAt(3), args.floatAt(4), args.floatAt(5),
                )
                METHOD_ON_FLING -> onFlingInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCALE_BEGIN -> onScaleBeginInternal(args.floatAt(0), args.floatAt(1))
                METHOD_ON_SCALE -> onScaleInternal(args.floatAt(0), args.floatAt(1), args.floatAt(2))
                METHOD_ON_DOWN -> onDownInternal()
                METHOD_ON_UP -> onUpInternal()
                METHOD_ON_POINTER_DOWN -> onPointerDownInternal()
                // Posted only for the render they request: single tap up, double
                // tap (acted on when confirmed), scale end and pointer up.
                METHOD_ON_SINGLE_TAP_UP, METHOD_ON_DOUBLE_TAP, METHOD_ON_SCALE_END, METHOD_ON_POINTER_UP -> Unit
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
        willFill = true
        val oldCurrentIndex = currentIndex

        // Dispatch method
        dispatchMethod()

        val time = AnimationTime.get()
        val animatingLayoutManager = this.layoutManager
        if (animatingLayoutManager != null && animatingLayoutManager.onUpdateAnimation(time)) {
            invalidate()
        }

        fill()
        willFill = false

        super.render(canvas)
        edgeView.render(canvas)

        val newCurrentIndex: Int
        val layoutManager = this.layoutManager
        if (layoutManager != null) {
            newCurrentIndex = layoutManager.getCurrentIndex()
            currentScrollFraction = layoutManager.currentScrollFraction
        } else {
            newCurrentIndex = GalleryPageView.INVALID_INDEX
            currentScrollFraction = 0f
        }
        currentIndex = newCurrentIndex

        if (oldCurrentIndex != newCurrentIndex && listener != null) {
            listener.onUpdateCurrentIndex(newCurrentIndex)
        }

        updatePageTransforms()
    }

    /**
     * Enable/disable the per-frame page-transform collection. Its only
     * consumer is the reader-stamps overlay (hidden by default); while the
     * overlay is invisible the per-visible-page allocations and the
     * main-thread notify per geometry change are pure waste on the render
     * hot path. Enabling requests a render so a fresh snapshot lands
     * without waiting for the next user scroll. Callable from any thread.
     */
    fun setPageTransformCollectionEnabled(enabled: Boolean) {
        if (pageTransformCollectionEnabled == enabled) {
            return
        }
        pageTransformCollectionEnabled = enabled
        if (enabled) {
            invalidate()
        }
    }

    @RenderThread
    private fun updatePageTransforms() {
        if (!pageTransformCollectionEnabled) {
            return
        }
        val layoutManager = this.layoutManager
        transformScratch.clear()
        if (layoutManager != null) {
            layoutManager.collectPageTransforms(transformScratch)
        }
        // List.equals directly, as Java did (no Intrinsics.areEqual step).
        if (transformScratch.equals(pageTransforms)) {
            return
        }
        pageTransforms = Collections.unmodifiableList(ArrayList(transformScratch))
        listener?.onPageTransformsChanged()
    }

    @RenderThread
    fun findPageByIndex(id: Int): GalleryPageView? {
        val layoutManager = this.layoutManager
        if (layoutManager != null) {
            return layoutManager.findPageByIndex(id)
        } else {
            return null
        }
    }

    @JvmName("obtainPage")
    internal fun obtainPage(): GalleryPageView {
        var page = galleryPageViewPool.acquire()
        if (page == null) {
            page = GalleryPageView(
                pageTextTexture,
                progressColor, pageBackgroundColor, progressSize,
                pageMinHeight, pageInfoInterval,
            )
        }
        return page
    }

    @JvmName("releasePage")
    internal fun releasePage(page: GalleryPageView) {
        galleryPageViewPool.release(page)
    }

    /**
     * Indeterminate GLProgressView
     */
    @JvmName("obtainProgress")
    internal fun obtainProgress(): GLProgressView {
        val progress: GLProgressView
        val progressCache = this.progressCache
        if (progressCache != null) {
            progress = progressCache
            this.progressCache = null
        } else {
            progress = GLProgressView()
            progress.setColor(progressColor)
            progress.setBgColor(pageBackgroundColor)
            progress.isIndeterminate = true
            progress.minimumWidth = progressSize
            progress.minimumHeight = progressSize
        }
        return progress
    }

    /**
     * @param progress Indeterminate GLProgressView
     */
    @JvmName("releaseProgress")
    internal fun releaseProgress(progress: GLProgressView) {
        progressCache = progress
    }

    @JvmName("obtainErrorView")
    internal fun obtainErrorView(): GLTextureView {
        val errorView: GLTextureView
        val errorViewCache = this.errorViewCache
        if (errorViewCache != null) {
            errorView = errorViewCache
            this.errorViewCache = null
        } else {
            errorView = GLTextureView()
        }
        return errorView
    }

    private fun unbindErrorView(errorView: GLTextureView) {
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

        val texture = StringTexture.newInstance(error, errorTextSize.toFloat(), errorTextColor)
        errorView.texture = texture
    }

    @JvmName("releaseErrorView")
    internal fun releaseErrorView(errorView: GLTextureView) {
        unbindErrorView(errorView)
        errorViewCache = errorView
    }

    fun onTransferEnd() {
        if (listener == null) {
            return
        }
        listener.onAutoTransferDone()
    }

    abstract class Adapter {

        @JvmField
        protected var galleryView: GalleryView? = null

        @JvmName("setGalleryView")
        internal fun setGalleryView(galleryView: GalleryView) {
            this.galleryView = galleryView
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

    abstract class LayoutManager(@JvmField protected val galleryView: GalleryView) {

        abstract fun onAttach(adapter: Adapter)

        abstract fun onDetach(): Adapter

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
        abstract val isReachEnd: Boolean

        /**
         * Collects current visible pages' image screen rects. Called on the
         * render thread after each render pass; implementations add one
         * PageTransform per attached page whose image is loaded.
         */
        abstract fun collectPageTransforms(out: MutableList<PageTransform>)

        abstract val isTapOrPressEnable: Boolean

        abstract fun findPageByIndex(index: Int): GalleryPageView?

        /**
         * @return [GalleryPageView.INVALID_INDEX] for error
         */
        abstract fun getCurrentIndex(): Int

        abstract fun setCurrentIndex(index: Int)

        abstract fun getIndexUnder(x: Float, y: Float): Int

        internal abstract val internalCurrentIndex: Int

        /**
         * Set a pending intra-page scroll fraction (0.0 ~ 1.0) on
         * the given target page, to apply on the next successful
         * fill where that page is laid out and loaded. The page
         * index is passed explicitly because the layout manager's
         * internal index can drift away from the caller's intent
         * (fillPages sets index to the first page in pages, which
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
        open val currentScrollFraction: Float
            get() = 0f

        protected fun placeCenter(view: GLView) {
            val spec = GLView.MeasureSpec.makeMeasureSpec(
                GLView.LayoutParams.WRAP_CONTENT,
                GLView.LayoutParams.WRAP_CONTENT,
            )
            view.measure(spec, spec)
            val viewWidth = view.measuredWidth
            val viewHeight = view.measuredHeight
            val viewLeft = galleryView.width / 2 - viewWidth / 2
            val viewTop = galleryView.height / 2 - viewHeight / 2
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
            if (!galleryView.getBoundsOf(image, imageBounds)) {
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
        fun sanitizeLayoutMode(layoutMode: Int): Int = when (layoutMode) {
            LAYOUT_LEFT_TO_RIGHT, LAYOUT_RIGHT_TO_LEFT, LAYOUT_TOP_TO_BOTTOM -> layoutMode
            else -> LAYOUT_LEFT_TO_RIGHT
        }

        @ScaleMode
        @JvmStatic
        fun sanitizeScaleMode(scaleMode: Int): Int = when (scaleMode) {
            SCALE_ORIGIN, SCALE_FIT_WIDTH, SCALE_FIT_HEIGHT, SCALE_FIT, SCALE_FIXED -> scaleMode
            else -> SCALE_FIT
        }

        @StartPosition
        @JvmStatic
        fun sanitizeStartPosition(startPosition: Int): Int = when (startPosition) {
            START_POSITION_TOP_LEFT, START_POSITION_TOP_RIGHT, START_POSITION_BOTTOM_LEFT,
            START_POSITION_BOTTOM_RIGHT, START_POSITION_CENTER -> startPosition
            else -> START_POSITION_TOP_LEFT
        }
    }
}
