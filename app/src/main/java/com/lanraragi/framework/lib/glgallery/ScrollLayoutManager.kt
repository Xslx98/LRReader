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
    "UnsafeCallOnNullableType",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "LargeClass",
)

package com.lanraragi.framework.lib.glgallery

import android.content.Context
import android.util.Log
import com.lanraragi.framework.lib.glview.anim.Animation
import com.lanraragi.framework.lib.glview.anim.AnimatorDurationScale
import com.lanraragi.framework.lib.glview.anim.FloatAnimation
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.framework.lib.glview.widget.GLEdgeView
import com.lanraragi.framework.lib.glview.widget.GLProgressView
import com.lanraragi.framework.lib.glview.widget.GLTextureView
import com.lanraragi.reader.util.AnimationUtils
import com.lanraragi.reader.util.AssertUtils
import com.lanraragi.reader.util.MathUtils
import kotlin.math.max
import kotlin.math.min

internal class ScrollLayoutManager(
    context: Context,
    galleryView: GalleryView,
    private var interval: Int,
) : GalleryView.LayoutManager(galleryView) {

    private var adapter: GalleryView.Adapter? = null

    private var progress: GLProgressView? = null
    private var errorStr: String? = null
    private var errorView: GLTextureView? = null
    private val pages = ArrayDeque<GalleryPageView>()
    private val tempPages = ArrayDeque<GalleryPageView>()

    private var scale = 0.5f
    private var offsetX = 0
    private var offsetY = 0
    private var deltaX = 0
    private var deltaY = 0
    private var keepTopPageIndex = GalleryPageView.INVALID_INDEX
    private var keepTop = INVALID_TOP
    private var firstShownPageIndex = GalleryPageView.INVALID_INDEX
    private var scrollUp = false
    private var flingUp = false
    private var stopAnimationFinger = false

    private val pageFling: PageFling = PageFling(context)
    private val smoothScaler: SmoothScaler = SmoothScaler()
    private val overScroller: OverScroller = OverScroller()

    // Current index
    private var index = 0

    private var bottomStateBottom = 0
    private var bottomStateHasNext = false

    // Pending intra-page scroll fraction to apply on the first fill
    // where the target page is laid out and loaded. NaN = nothing
    // pending. Cleared on user scroll/fling/touch so we don't fight
    // the user. See setStartScrollFraction / onFill / onDown.
    private var pendingStartFraction = Float.NaN
    private var pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX

    fun setInterval(interval: Int) {
        if (this.interval == interval) {
            return
        }

        if (this.adapter != null) {
            val index = internalCurrentIndex
            val adapter = onDetach()
            this.interval = interval
            onAttach(adapter)
            setCurrentIndex(index)
            galleryView.requestFill()
        } else {
            this.interval = interval
        }
    }

    private fun resetParameters() {
        scale = 1.0f
        offsetX = 0
        offsetY = 0
        deltaX = 0
        deltaY = 0
        keepTopPageIndex = GalleryPageView.INVALID_INDEX
        keepTop = INVALID_TOP
        firstShownPageIndex = GalleryPageView.INVALID_INDEX
        scrollUp = false
        flingUp = false
        stopAnimationFinger = false
        pendingStartFraction = Float.NaN
        pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
    }

    // Return true for animations are running
    private fun cancelAllAnimations(): Boolean {
        val running = pageFling.isRunning ||
            smoothScaler.isRunning ||
            overScroller.isRunning
        pageFling.cancel()
        smoothScaler.cancel()
        overScroller.cancel()
        return running
    }

    override fun onAttach(adapter: GalleryView.Adapter?) {
        AssertUtils.assertNull("The ScrollLayoutManager is attached", this.adapter)
        AssertUtils.assertNotNull("The iterator is null", adapter)
        this.adapter = adapter
        // Reset parameters
        resetParameters()
    }

    private fun removeProgress() {
        val progress = this.progress
        if (progress != null) {
            galleryView.removeComponent(progress)
            galleryView.releaseProgress(progress)
            this.progress = null
        }
    }

    private fun removeErrorView() {
        val errorView = this.errorView
        if (errorView != null) {
            galleryView.removeComponent(errorView)
            galleryView.releaseErrorView(errorView)
            this.errorView = null
            errorStr = null
        }
    }

    private fun removePage(page: GalleryPageView) {
        galleryView.removeComponent(page)
        adapter!!.unbind(page)
        galleryView.releasePage(page)
    }

    private fun removeAllPages() {
        for (page in pages) {
            removePage(page)
        }
        pages.clear()
    }

    override fun onDetach(): GalleryView.Adapter? {
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", adapter)

        // Cancel all animations
        cancelAllAnimations()

        // Remove all view
        removeProgress()
        removeErrorView()
        removeAllPages()

        // Clear iterator
        val iterator = adapter
        adapter = null

        return iterator
    }

    private fun obtainPage(): GalleryPageView {
        val page = galleryView.obtainPage()
        page.imageView.setScaleOffset(ImageView.SCALE_FIT, ImageView.START_POSITION_TOP_RIGHT, 1.0f)
        return page
    }

    private fun getPageForIndex(pages: MutableList<GalleryPageView>, index: Int, remove: Boolean): GalleryPageView? {
        val iterator = pages.iterator()
        while (iterator.hasNext()) {
            val page = iterator.next()
            if (index == page.index) {
                if (remove) {
                    iterator.remove()
                }
                return page
            }
        }
        return null
    }

    private fun isInScreen(page: GalleryPageView): Boolean {
        val height = galleryView.height
        val bound = page.bounds()
        val pageTop = bound.top
        val pageBottom = bound.bottom
        return (pageTop >= 0 && pageTop < height) || (pageBottom > 0 && pageBottom <= height) ||
            (pageTop < 0 && pageBottom > height)
    }

    private val reservation: Float
        get() = max(RESERVATION, ((1 + 2 * RESERVATION) * scale - 1) / 2)

    private fun fillPages(startIndexArg: Int, startOffsetArg: Int) {
        var startIndex = startIndexArg
        var startOffset = startOffsetArg
        val adapter = this.adapter!!
        val galleryView = this.galleryView
        val pages = this.pages
        val tempPages = this.tempPages
        val width = galleryView.width
        val height = galleryView.height
        val pageWidth = (width * scale).toInt()
        val size = adapter.size()
        val interval = this.interval
        val reservation = this.reservation
        val minY = (-height * reservation).toInt()
        val maxY = (height * (1 + reservation)).toInt()
        val widthSpec = GLView.MeasureSpec.makeMeasureSpec(pageWidth, GLView.MeasureSpec.EXACTLY)
        val heightSpec = GLView.MeasureSpec.makeMeasureSpec(height, GLView.MeasureSpec.UNSPECIFIED)

        // Fix start index and start offset
        if (startIndex < 0) {
            startIndex = 0
            startOffset = 0
        } else if (startIndex >= size) {
            startIndex = size - 1
            startOffset = 0
        } else if (startOffset < minY) {
            while (true) {
                val page = getPageForIndex(pages, startIndex, false)
                if (null == page) {
                    startOffset = minY
                    break
                } else {
                    page.measure(widthSpec, heightSpec)
                    if (startOffset + page.height > minY) {
                        break
                    } else if (size - 1 == startIndex) {
                        startOffset = 0
                        break
                    } else {
                        ++startIndex
                        startOffset += page.height + interval
                        if (startOffset >= minY) {
                            break
                        }
                    }
                }
            }
        } else if (startOffset >= maxY) {
            if (0 == startIndex) {
                startOffset = 0
            } else {
                --startIndex
                var startBottomOffset = startOffset - interval
                while (true) {
                    val page = getPageForIndex(pages, startIndex, false)
                    if (null == page) {
                        startOffset = maxY - 1
                        break
                    } else {
                        page.measure(widthSpec, heightSpec)
                        startOffset = startBottomOffset - page.height
                        if (startOffset < maxY) {
                            break
                        } else if (0 == startIndex) {
                            startOffset = 0
                            break
                        } else {
                            --startIndex
                            startBottomOffset = startOffset - interval
                        }
                    }
                }
            }
        }

        // Put page to temp list
        tempPages.addAll(pages)
        pages.clear()

        // Sanitize offsetX
        val margin = pageWidth - width
        if (margin >= 0) {
            offsetX = MathUtils.clamp(offsetX, -margin, 0)
        } else {
            offsetX = -margin / 2
        }

        // Layout start page
        var page = getPageForIndex(tempPages, startIndex, true)
        if (null == page) {
            page = obtainPage()
            galleryView.addComponent(page)
            adapter.bind(page, startIndex)
        }
        pages.add(page)
        page.measure(widthSpec, heightSpec)
        page.layout(offsetX, startOffset, offsetX + pageWidth, startOffset + page.measuredHeight)

        // Prepare for check up and down
        var bottomOffset = startOffset - interval
        var topOffset = startOffset + page.measuredHeight + interval

        // Check up
        var index = startIndex - 1
        while (bottomOffset > minY && index >= 0) {
            page = getPageForIndex(tempPages, index, true)
            if (null == page) {
                page = obtainPage()
                galleryView.addComponent(page)
                adapter.bind(page, index)
            }
            pages.addFirst(page)
            page.measure(widthSpec, heightSpec)
            page.layout(offsetX, bottomOffset - page.measuredHeight, offsetX + pageWidth, bottomOffset)
            // Update
            bottomOffset -= page.measuredHeight + interval
            index--
        }

        // Avoid space in top
        page = pages.first()
        if (0 == page.index && page.bounds().top > 0) {
            val offset = -page.bounds().top
            for (p in pages) {
                p.offsetTopAndBottom(offset)
            }
            topOffset += offset
        }

        // Check down
        index = startIndex + 1
        while (topOffset < maxY && index < size) {
            page = getPageForIndex(tempPages, index, true)
            if (null == page) {
                page = obtainPage()
                galleryView.addComponent(page)
                adapter.bind(page, index)
            }
            pages.addLast(page)
            page.measure(widthSpec, heightSpec)
            page.layout(offsetX, topOffset, offsetX + pageWidth, topOffset + page.measuredHeight)
            // Update
            topOffset += page.measuredHeight + interval
            index++
        }

        // Avoid space in bottom
        if (size - 1 == pages.last().index) {
            while (true) {
                page = pages.last()
                val pagesBottom = page.bounds().bottom
                if (pagesBottom >= height) {
                    break
                }
                page = pages.first()
                index = page.index
                if (0 == index) {
                    break
                }
                --index
                val pagesTop = page.bounds().top

                page = getPageForIndex(tempPages, index, true)
                if (null == page) {
                    page = obtainPage()
                    galleryView.addComponent(page)
                    adapter.bind(page, index)
                }
                pages.addFirst(page)
                page.measure(widthSpec, heightSpec)

                val offset = min(height - pagesBottom, page.measuredHeight)
                for (p in pages) {
                    p.offsetTopAndBottom(offset)
                }
                val bottom = pagesTop - interval + offset
                page.layout(offsetX, bottom - page.measuredHeight, offsetX + pageWidth, bottom)
            }
        }

        // Remove remain page
        for (p in tempPages) {
            removePage(p)
        }
        tempPages.clear()

        // Update state
        if (!pages.isEmpty()) {
            page = pages.first()
            this.index = page.index
            offsetY = page.bounds().top
        }
    }

    override fun onFill() {
        val adapter = this.adapter
        val galleryView = this.galleryView
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", adapter)

        val size = adapter!!.size()
        val error = adapter.getError()

        if (size == GalleryProvider.STATE_WAIT) { // Wait here, show progress bar
            // Remove error view and all pages
            removeErrorView()
            removeAllPages()

            // Ensure progress
            var progress = this.progress
            if (progress == null) {
                progress = galleryView.obtainProgress()
                this.progress = progress
                galleryView.addComponent(progress)
            }

            // Place progress center
            placeCenter(progress)
        } else if (size <= GalleryProvider.STATE_ERROR || size == 0) { // Get error or empty, show error text
            // Ensure error is not null
            val errorStr: String = if (0 == size) {
                galleryView.emptyStr
            } else {
                error ?: galleryView.defaultErrorStr
            }

            // Remove progress and all pages
            removeProgress()
            removeAllPages()

            // Ensure error view
            var errorView = this.errorView
            if (errorView == null) {
                errorView = galleryView.obtainErrorView()
                this.errorView = errorView
                galleryView.addComponent(errorView)
            }

            // Update error string
            if (errorStr != this.errorStr) {
                this.errorStr = errorStr
                galleryView.bindErrorView(errorView, errorStr)
            }

            // Place error view center
            placeCenter(errorView)
        } else {
            val pages = this.pages

            // Remove progress and error view
            removeProgress()
            removeErrorView()

            // Ensure index in range
            var index = this.index
            if (index < 0) {
                Log.e(TAG, "index < 0, index = $index")
                index = 0
                this.index = index
                removeAllPages()
            } else if (index >= size) {
                Log.e(TAG, "index >= size, index = $index, size = $size")
                index = size - 1
                this.index = index
                removeAllPages()
            }

            // Find keep index and keep top
            var keepTop = INVALID_TOP
            val keepTopIndex: Int
            if (GalleryPageView.INVALID_INDEX != keepTopPageIndex) {
                keepTopIndex = keepTopPageIndex
                keepTop = this.keepTop
            } else if (GalleryPageView.INVALID_INDEX != firstShownPageIndex) {
                keepTopIndex = firstShownPageIndex
            } else {
                keepTopIndex = GalleryPageView.INVALID_INDEX
            }
            if (GalleryPageView.INVALID_INDEX != keepTopIndex && INVALID_TOP == keepTop) {
                keepTop = offsetY
                for (page in pages) {
                    // Check keep page
                    if (keepTopIndex == page.index) {
                        break
                    }
                    keepTop += page.height + interval
                }
            }

            val startIndex: Int
            val startOffset: Int
            if (GalleryPageView.INVALID_INDEX != keepTopIndex) {
                startIndex = keepTopIndex
                startOffset = keepTop
            } else {
                startIndex = this.index
                startOffset = offsetY
            }
            fillPages(startIndex, startOffset)

            // Get first shown image
            firstShownPageIndex = GalleryPageView.INVALID_INDEX
            for (page in this.pages) {
                // Check first shown loaded page
                if ((scrollUp || flingUp) && !page.isLoaded) {
                    continue
                }

                if (isInScreen(page)) {
                    firstShownPageIndex = page.index
                    keepTopPageIndex = page.index
                    break
                }
            }

            // Apply pending intra-page restore via the keep-top
            // anchors (keepTopPageIndex / keepTop) — the same
            // mechanism setCurrentIndex / onScale already use to pin a
            // page at a specific viewport Y across fills. This is the
            // GL-side analogue of
            // LinearLayoutManager.scrollToPositionWithOffset(position,
            // offset): "place the top of [position] at [offset] from
            // the viewport top".
            //
            // We can only finalize once the target page reports a
            // real loaded height — fraction × placeholder-min-height
            // would yield a tiny offset. Until then we leave the
            // pending value alone and re-check on the next fill — the
            // GalleryAdapter pipes a requestFill() through after every
            // onPageSucceed so this branch gets a second chance when
            // the target's image arrives. onScroll / onDown clear
            // pending the moment the user interacts.
            if (!pendingStartFraction.isNaN() &&
                pendingStartFractionPageIndex != GalleryPageView.INVALID_INDEX
            ) {
                var target: GalleryPageView? = null
                for (p in this.pages) {
                    if (p.index == pendingStartFractionPageIndex) {
                        target = p
                        break
                    }
                }
                if (target != null && target.isLoaded && target.height > 0) {
                    val desiredTop = -Math.round(pendingStartFraction * target.height)
                    Log.d(
                        TAG,
                        "[FRACTION] applying: page=" + pendingStartFractionPageIndex +
                            " fraction=" + pendingStartFraction +
                            " pageHeight=" + target.height +
                            " desiredTop=" + desiredTop,
                    )
                    // Pin the target page at desiredTop via keep-top
                    // anchors. fillPages reads (keepTopPageIndex,
                    // keepTop) at the start of each fill (line 484)
                    // and uses them as (startIndex, startOffset),
                    // exactly what we want. The pin survives until
                    // onScroll/onFling clear it on user input
                    // (lines ~744, ~756).
                    keepTopPageIndex = pendingStartFractionPageIndex
                    this.keepTop = desiredTop
                    pendingStartFraction = Float.NaN
                    pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
                    // Force an immediate re-fill within this render
                    // cycle so the new anchor lands on screen now;
                    // galleryView.requestFill() would be a no-op
                    // here since we're inside onFill
                    // (GalleryView's requestFillEnabled == false).
                    this.galleryView.forceFill()
                }
            }
        }
    }

    override fun onDown() {
        deltaX = 0
        deltaY = 0
        scrollUp = false
        stopAnimationFinger = cancelAllAnimations()
        // User touch wins: drop any pending start-fraction restore so we
        // don't snap the page out from under the finger if the target
        // page's image happens to load mid-gesture.
        pendingStartFraction = Float.NaN
        pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
    }

    override fun onUp() {
        scrollUp = false
        galleryView.edgeView.onRelease()
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float) {
        if (pages.size <= 0) {
            return
        }

        val startScale = scale
        val endScale: Float
        if (startScale < MAX_SCALE - SCALE_ERROR) {
            endScale = MAX_SCALE
        } else {
            endScale = MIN_SCALE
        }

        // Follows the system animator scale; at 0 the zoom is instant.
        smoothScaler.startSmoothScaler(
            x, y, startScale, endScale,
            AnimatorDurationScale.scale(DOUBLE_TAP_SCALE_DURATION).toInt(),
        )
    }

    fun overScrollEdge(dx: Int, dy: Int, x: Float, y: Float) {
        val edgeView = galleryView.edgeView

        deltaX += dx
        deltaY += dy

        if (deltaX < 0) {
            edgeView.onPull((-deltaX).toFloat(), y, GLEdgeView.LEFT)
            if (!edgeView.isFinished(GLEdgeView.RIGHT)) {
                edgeView.onRelease(GLEdgeView.RIGHT)
            }
        } else if (deltaX > 0) {
            edgeView.onPull(deltaX.toFloat(), y, GLEdgeView.RIGHT)
            if (!edgeView.isFinished(GLEdgeView.LEFT)) {
                edgeView.onRelease(GLEdgeView.LEFT)
            }
        }

        if (deltaY < 0) {
            edgeView.onPull((-deltaY).toFloat(), x, GLEdgeView.TOP)
            if (!edgeView.isFinished(GLEdgeView.BOTTOM)) {
                edgeView.onRelease(GLEdgeView.BOTTOM)
            }
        } else if (deltaY > 0) {
            edgeView.onPull(deltaY.toFloat(), x, GLEdgeView.BOTTOM)
            if (!edgeView.isFinished(GLEdgeView.TOP)) {
                edgeView.onRelease(GLEdgeView.TOP)
            }
        }
    }

    private fun getBottomState() {
        val pages: List<GalleryPageView> = this.pages
        var bottom = offsetY
        var i = 0
        for (page in pages) {
            if (i != 0) {
                bottom += interval
            }
            bottom += page.height
            i++
        }
        val hasNext = index + pages.size < adapter!!.size()

        bottomStateBottom = bottom
        bottomStateHasNext = hasNext
    }

    /**
     * Whether the strip is scrolled to (or past) the bottom edge of the
     * last page — the exact condition that gates the BOTTOM over-scroll
     * branch in [onPageRight]. Reusing [getBottomState]
     * out-of-band is safe: it only writes the bottomState* scratch
     * fields, and every other caller recomputes them immediately before
     * reading. It does dereference adapter unconditionally though, so
     * guard the detached/empty cases first.
     *
     * Known acceptable edge: a strip that fully fits on one screen
     * reports true even during a TOP over-scroll, because the last
     * page's bottom is always within the viewport then. Tolerable — the
     * whole archive is visible, so "at the end" is still accurate.
     */
    override val isReachEnd: Boolean
        get() {
            if (adapter == null || pages.isEmpty() || adapter!!.size() <= 0) {
                return false
            }
            getBottomState()
            return !bottomStateHasNext && bottomStateBottom <= galleryView.height
        }

    override fun collectPageTransforms(out: MutableList<PageTransform>) {
        for (page in pages) {
            collectPageTransform(out, page)
        }
    }

    // True for get top or bottom
    private fun scrollInternal(dx: Float, dy: Float, fling: Boolean, x: Float, y: Float): Boolean {
        if (pages.size <= 0) {
            return false
        }

        val galleryView = this.galleryView
        val width = galleryView.width
        val height = galleryView.height
        val pageWidth = (width * scale).toInt()
        val reservation = this.reservation
        var requestFill = false
        var result = false

        val margin = pageWidth - width
        val dxInt = dx.toInt()
        if (margin > 0 && 0 != dxInt) {
            val oldOffsetX = offsetX
            val exceptOffsetX = oldOffsetX - dxInt
            offsetX = MathUtils.clamp(exceptOffsetX, -margin, 0)
            if (offsetX != oldOffsetX) {
                requestFill = true
            }
            // Do not show over scroll effect for left and right
            /*
            int extraOffsetX = offsetX - exceptOffsetX;
            if (0 != extraOffsetX) {
                overScrollEdge(extraOffsetX, 0, x, y);
            }
            */
        }

        var remainY = dy.toInt()
        while (remainY != 0) {
            if (remainY < 0) { // Try to show top
                val limit: Int
                if (index > 0) {
                    limit = (-height * reservation).toInt() + interval
                } else {
                    limit = 0
                }

                if (offsetY - remainY <= limit) {
                    offsetY -= remainY
                    remainY = 0
                    requestFill = true
                    deltaX = 0
                    deltaY = 0
                } else {
                    if (index > 0) {
                        offsetY = limit
                        remainY = remainY + limit - offsetY
                        // Offset one pixel to avoid infinite loop
                        ++offsetY
                        ++remainY
                        galleryView.forceFill()
                        requestFill = false
                        deltaX = 0
                        deltaY = 0
                    } else {
                        if (offsetY != limit) {
                            offsetY = limit
                            requestFill = true
                        }
                        if (!fling) {
                            overScrollEdge(0, remainY + limit - offsetY, x, y)
                        }
                        remainY = 0
                        result = true
                    }
                }
            } else { // Try to show bottom
                getBottomState()
                val bottom = bottomStateBottom
                val hasNext = bottomStateHasNext

                var limit: Int
                if (hasNext) {
                    limit = (height * (1 + reservation)).toInt() - interval
                } else {
                    limit = height
                }
                // Fix limit for page not fill screen
                limit = min(bottom, limit)

                if (bottom - remainY >= limit) {
                    offsetY -= remainY
                    remainY = 0
                    requestFill = true
                    deltaX = 0
                    deltaY = 0
                } else {
                    if (hasNext) {
                        offsetY -= bottom - limit
                        remainY = remainY + limit - bottom
                        // Offset one pixel to avoid infinite loop
                        --offsetY
                        --remainY
                        galleryView.forceFill()
                        requestFill = false
                        deltaX = 0
                        deltaY = 0
                    } else {
                        if (offsetY != limit) {
                            offsetY -= bottom - limit
                            requestFill = true
                        }
                        if (!fling) {
                            overScrollEdge(0, remainY + limit - bottom, x, y)
                        }
                        remainY = 0
                        result = true
                    }
                }
            }
        }

        if (requestFill) {
            this.galleryView.requestFill()
        }

        return result
    }

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        keepTopPageIndex = GalleryPageView.INVALID_INDEX
        keepTop = INVALID_TOP
        scrollUp = dy < 0
        scrollInternal(dx, dy, false, x, y)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (pages.size <= 0) {
            return
        }

        keepTopPageIndex = GalleryPageView.INVALID_INDEX
        keepTop = INVALID_TOP
        flingUp = velocityY > 0

        val maxX: Int
        val minX: Int
        val width = galleryView.width
        val pageWidth = (width * scale).toInt()
        val margin = pageWidth - width
        if (margin > 0) {
            maxX = -offsetX
            minX = -margin + offsetX
        } else {
            maxX = 0
            minX = 0
        }

        val maxY: Int
        if (index > 0) {
            maxY = Int.MAX_VALUE
        } else {
            maxY = -offsetY
        }

        getBottomState()
        val bottom = bottomStateBottom
        val hasNext = bottomStateHasNext
        val minY: Int
        if (hasNext) {
            minY = Int.MIN_VALUE
        } else {
            minY = galleryView.height - bottom
        }

        pageFling.startFling(
            velocityX.toInt(), minX, maxX,
            velocityY.toInt(), minY, maxY,
        )
    }

    override fun canScale(): Boolean = pages.size > 0

    override fun onScale(focusX: Float, focusY: Float, scale: Float) {
        if (pages.isEmpty()) {
            return
        }

        val oldScale = this.scale
        this.scale = MathUtils.clamp(oldScale * scale, MIN_SCALE, MAX_SCALE)
        val appliedScale = this.scale / oldScale

        if (oldScale != this.scale) {
            var page: GalleryPageView? = null
            // Keep scale page origin position
            for (p in pages) {
                if (p.bounds().top < focusY) {
                    page = p
                } else {
                    break
                }
            }

            if (null != page) {
                keepTopPageIndex = page.index
                keepTop = page.bounds().top

                galleryView.forceFill()
                val oldKeepTop = keepTop
                keepTop = INVALID_TOP

                // Apply scroll
                val newOffsetX = (focusX - ((focusX - offsetX) * appliedScale)).toInt()
                val newKeepTop: Int
                if (page.isLoaded) {
                    newKeepTop = (focusY - ((focusY - oldKeepTop) * appliedScale)).toInt()
                } else {
                    newKeepTop = oldKeepTop
                }
                scrollInternal(
                    (offsetX - newOffsetX).toFloat(), (oldKeepTop - newKeepTop).toFloat(),
                    false, focusX, focusY,
                )
            } else {
                Log.e(TAG, "Can't find target page")
                keepTopPageIndex = GalleryPageView.INVALID_INDEX
                keepTop = INVALID_TOP
                galleryView.forceFill()
            }
        }
    }

    override fun onUpdateAnimation(time: Long): Boolean {
        var invalidate = pageFling.calculate(time)
        invalidate = invalidate or smoothScaler.calculate(time)
        invalidate = invalidate or overScroller.calculate(time)
        return invalidate
    }

    override fun onDataChanged() {
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", adapter)

        // Cancel all animations
        cancelAllAnimations()
        // Remove all views
        removeProgress()
        removeErrorView()
        removeAllPages()
        // Reset parameters
        resetParameters()
        galleryView.requestFill()
    }

    override fun onPageLeft() {
        val size = adapter!!.size()
        if (size <= 0 || pages.isEmpty()) {
            return
        }

        ///////
        // UP
        ///////
        val galleryView = this.galleryView
        if (index == 0 && offsetY >= 0) {
            overScroller.overScroll(GLEdgeView.TOP)
            this.galleryView.onTransferEnd()
        } else {
            // Cancel all animations
            cancelAllAnimations()

            // Get first shown page
            var previousPage: GalleryPageView? = null
            var firstShownPage: GalleryPageView? = null
            for (p in pages) {
                if (isInScreen(p)) {
                    firstShownPage = p
                    break
                }
                previousPage = p
            }

            val height = galleryView.height
            val maxOffset = height - interval
            if (null == firstShownPage) {
                Log.e(TAG, "Can't find first shown page when paging left")
                offsetY += height / 2
            } else {
                val firstShownTop = firstShownPage.bounds().top
                if (firstShownTop >= 0) {
                    if (null == previousPage) {
                        Log.e(TAG, "Can't find previous page when paging left and offsetY == 0")
                        offsetY += height / 2
                    } else {
                        offsetY += min(maxOffset, -previousPage.bounds().top)
                    }
                } else {
                    offsetY += min(maxOffset, -firstShownTop)
                }
            }

            // Request fill
            this.galleryView.requestFill()
        }
    }

    override fun onPageRight() {
        val size = adapter!!.size()
        if (size <= 0 || pages.isEmpty()) {
            return
        }

        /////////
        // DOWN
        /////////
        val galleryView = this.galleryView
        getBottomState()
        val bottom = bottomStateBottom
        val hasNext = bottomStateHasNext
        if (!hasNext && bottom <= galleryView.height) {
            overScroller.overScroll(GLEdgeView.BOTTOM)
            this.galleryView.onTransferEnd()
        } else {
            // Cancel all animations
            cancelAllAnimations()

            // Get first shown page
            var lastShownPage: GalleryPageView? = null
            var nextPage: GalleryPageView? = null
            for (p in pages) {
                if (isInScreen(p)) {
                    lastShownPage = p
                } else if (null != lastShownPage) {
                    nextPage = p
                    break
                }
            }

            val height = galleryView.height
            val maxOffset = height - interval
            if (null == lastShownPage) {
                Log.e(TAG, "Can't find last shown page when paging left")
                offsetY -= height / 2
            } else {
                val lastShownBottom = lastShownPage.bounds().bottom
                if (lastShownBottom <= height) {
                    if (null == nextPage) {
                        Log.e(TAG, "Can't find previous page when paging left and offsetY == 0")
                        offsetY -= height / 2
                    } else {
                        offsetY -= min(maxOffset, nextPage.bounds().bottom - height)
                    }
                } else {
                    offsetY -= min(maxOffset, lastShownBottom - height)
                }
            }

            // Request fill
            this.galleryView.requestFill()
        }
    }

    override val isTapOrPressEnable: Boolean
        get() = !stopAnimationFinger

    override fun findPageByIndex(index: Int): GalleryPageView? {
        for (page in pages) {
            if (page.index == index) {
                return page
            }
        }
        return null
    }

    override fun getCurrentIndex(): Int {
        for (page in pages) {
            if (isInScreen(page)) {
                return page.index
            }
        }
        return GalleryPageView.INVALID_INDEX
    }

    /**
     * Schedule an intra-page scroll restore at the next successful
     * fill. The restore is anchored to the page index that's currently
     * active (the caller is expected to invoke
     * [setCurrentIndex] immediately before this), so the
     * pair forms a "jump to page N at fraction F" operation.
     *
     * Out-of-range fractions are clamped; NaN clears the pending value.
     */
    override fun setStartScrollFraction(pageIndex: Int, fraction: Float) {
        if (fraction.isNaN() || pageIndex < 0) {
            pendingStartFraction = Float.NaN
            pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
            Log.d(
                TAG,
                "[FRACTION] setStartScrollFraction cleared pending (pageIndex=" +
                    pageIndex + ", fraction=" + fraction + ")",
            )
            return
        }
        val clamped = MathUtils.clamp(fraction, 0f, 1f)
        if (clamped <= 0f) {
            pendingStartFraction = Float.NaN
            pendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
            Log.d(TAG, "[FRACTION] setStartScrollFraction <= 0 -> cleared pending")
            return
        }
        pendingStartFraction = clamped
        pendingStartFractionPageIndex = pageIndex
        Log.d(TAG, "[FRACTION] setStartScrollFraction page=" + pageIndex + " fraction=" + clamped)
        galleryView.requestFill()
    }

    /**
     * Compute the intra-page scroll fraction for the page that is
     * currently the "first visible" one. Pages that haven't been
     * measured yet, or empty layouts, return 0.
     */
    override val currentScrollFraction: Float
        get() {
            if (pages.isEmpty()) {
                return 0f
            }
            val currentIdx = getCurrentIndex()
            if (currentIdx == GalleryPageView.INVALID_INDEX) {
                return 0f
            }
            val page = findPageByIndex(currentIdx) ?: return 0f
            val height = page.height
            if (height <= 0) {
                return 0f
            }
            val fraction = (-page.bounds().top).toFloat() / height.toFloat()
            return MathUtils.clamp(fraction, 0f, 1f)
        }

    override fun setCurrentIndex(index: Int) {
        var size = adapter!!.size()
        if (size <= 0) {
            // Can't get size now, assume size is MAX
            size = Int.MAX_VALUE
        }
        if (index < 0 || index >= size) {
            return
        }

        keepTopPageIndex = index
        keepTop = INVALID_TOP

        if (pages.isEmpty()) {
            this.index = index
        } else {
            // Fix the index page
            var targetPage: GalleryPageView? = null
            for (page in pages) {
                if (page.index == index) {
                    targetPage = page
                    break
                }
            }

            if (targetPage != null) {
                // Cancel all animations
                cancelAllAnimations()
                offsetY -= targetPage.bounds().top
                // Request fill
                galleryView.requestFill()
            } else {
                this.index = index
                offsetY = 0
                // Cancel all animations
                cancelAllAnimations()
                // Remove all view
                removeProgress()
                removeErrorView()
                removeAllPages()
                // Request fill
                galleryView.requestFill()
            }
        }
    }

    override fun getIndexUnder(x: Float, y: Float): Int {
        if (pages.isEmpty()) {
            return GalleryPageView.INVALID_INDEX
        } else {
            val intX = x.toInt()
            val intY = y.toInt()
            for (page in pages) {
                if (page.bounds().contains(intX, intY)) {
                    return page.index
                }
            }
            return GalleryPageView.INVALID_INDEX
        }
    }

    override val internalCurrentIndex: Int
        get() {
            var currentIndex = getCurrentIndex()
            if (currentIndex == GalleryPageView.INVALID_INDEX) {
                currentIndex = index
            }
            return currentIndex
        }

    private inner class PageFling(context: Context) : Fling(context) {

        private var velocityX = 0
        private var velocityY = 0
        private var dx = 0
        private var dy = 0
        private var lastX = 0
        private var lastY = 0

        fun startFling(
            velocityX: Int,
            minX: Int,
            maxX: Int,
            velocityY: Int,
            minY: Int,
            maxY: Int,
        ) {
            this.velocityX = velocityX
            this.velocityY = velocityY
            dx = (getSplineFlingDistance(velocityX) * Math.signum(velocityX.toFloat())).toInt()
            dy = (getSplineFlingDistance(velocityY) * Math.signum(velocityY.toFloat())).toInt()
            lastX = 0
            lastY = 0
            var durationX = getSplineFlingDuration(velocityX)
            var durationY = getSplineFlingDuration(velocityY)

            if (dx < minX) {
                durationX = adjustDuration(0, dx, minX, durationX)
                dx = minX
            }
            if (dx > maxX) {
                durationX = adjustDuration(0, dx, maxX, durationX)
                dx = maxX
            }
            if (dy < minY) {
                durationY = adjustDuration(0, dy, minY, durationY)
                dy = minY
            }
            if (dy > maxY) {
                durationY = adjustDuration(0, dy, maxY, durationY)
                dy = maxY
            }

            if (dx == 0 && dy == 0) {
                return
            }

            duration = max(durationX, durationY).toLong()
            start()
            galleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            val x = (dx * progress).toInt()
            val y = (dy * progress).toInt()
            val offsetX = x - lastX
            val offsetY = y - lastY
            if (scrollInternal((-offsetX).toFloat(), (-offsetY).toFloat(), true, 0f, 0f)) {
                cancel()
                onFinish()
            }
            lastX = x
            lastY = y
        }

        override fun onFinish() {
            flingUp = false

            val index = this@ScrollLayoutManager.index

            val topEdge = index <= 0 && offsetY >= 0

            getBottomState()
            val bottom = bottomStateBottom
            val hasNext = bottomStateHasNext
            val bottomEdge = !hasNext && bottom <= galleryView.height

            if (topEdge && bottomEdge) {
                return
            }

            val edgeView = galleryView.edgeView
            if (topEdge && edgeView.isFinished(GLEdgeView.TOP)) {
                edgeView.onAbsorb(velocityY, GLEdgeView.TOP)
            } else if (bottomEdge && edgeView.isFinished(GLEdgeView.BOTTOM)) {
                edgeView.onAbsorb(-velocityY, GLEdgeView.BOTTOM)
            }
        }
    }

    private inner class SmoothScaler : Animation() {

        private var focusX = 0f
        private var focusY = 0f
        private var startScale = 0f
        private var endScale = 0f
        private var lastScale = 0f

        init {
            interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        }

        fun startSmoothScaler(
            focusX: Float,
            focusY: Float,
            startScale: Float,
            endScale: Float,
            duration: Int,
        ) {
            this.focusX = focusX
            this.focusY = focusY
            this.startScale = startScale
            this.endScale = endScale
            lastScale = startScale
            this.duration = duration.toLong()
            start()
            galleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            if (pages.size <= 0) {
                return
            }

            val scale = MathUtils.lerp(startScale, endScale, progress)
            onScale(focusX, focusY, scale / lastScale)
            lastScale = scale
        }
    }

    private inner class OverScroller : FloatAnimation() {

        private var direction = 0
        private var position = 0

        init {
            duration = 300L
        }

        fun overScroll(direction: Int) {
            this.direction = direction
            val range: Int
            when (this.direction) {
                GLEdgeView.LEFT, GLEdgeView.RIGHT -> {
                    range = galleryView.width / 7
                    position = galleryView.height / 2
                }
                GLEdgeView.TOP, GLEdgeView.BOTTOM -> {
                    range = galleryView.height / 7
                    position = galleryView.width / 2
                }
                else -> return
            }
            setRange(0f, range.toFloat())
            start()
            galleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            super.onCalculate(progress)
            galleryView.edgeView.onPull(get(), position.toFloat(), direction)
        }

        override fun onFinish() {
            galleryView.edgeView.onRelease(direction)
        }
    }

    companion object {
        private val TAG: String = ScrollLayoutManager::class.java.simpleName

        private const val RESERVATION = 1f

        private const val MAX_SCALE = 2.0f
        private const val MIN_SCALE = 1.0f
        private const val SCALE_ERROR = 0.01f

        private const val INVALID_TOP = Int.MAX_VALUE

        private const val DOUBLE_TAP_SCALE_DURATION = 300L
    }
}
