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
    private var mInterval: Int,
) : GalleryView.LayoutManager(galleryView) {

    private var mAdapter: GalleryView.Adapter? = null

    private var mProgress: GLProgressView? = null
    private var mErrorStr: String? = null
    private var mErrorView: GLTextureView? = null
    private val mPages = ArrayDeque<GalleryPageView>()
    private val mTempPages = ArrayDeque<GalleryPageView>()

    private var mScale = 0.5f
    private var mOffsetX = 0
    private var mOffsetY = 0
    private var mDeltaX = 0
    private var mDeltaY = 0
    private var mKeepTopPageIndex = GalleryPageView.INVALID_INDEX
    private var mKeepTop = INVALID_TOP
    private var mFirstShownPageIndex = GalleryPageView.INVALID_INDEX
    private var mScrollUp = false
    private var mFlingUp = false
    private var mStopAnimationFinger = false

    private val mPageFling: PageFling = PageFling(context)
    private val mSmoothScaler: SmoothScaler = SmoothScaler()
    private val mOverScroller: OverScroller = OverScroller()

    // Current index
    private var mIndex = 0

    private var mBottomStateBottom = 0
    private var mBottomStateHasNext = false

    // Pending intra-page scroll fraction to apply on the first fill
    // where the target page is laid out and loaded. NaN = nothing
    // pending. Cleared on user scroll/fling/touch so we don't fight
    // the user. See setStartScrollFraction / onFill / onDown.
    private var mPendingStartFraction = Float.NaN
    private var mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX

    fun setInterval(interval: Int) {
        if (mInterval == interval) {
            return
        }

        if (mAdapter != null) {
            val index = getInternalCurrentIndex()
            val adapter = onDetach()
            mInterval = interval
            onAttach(adapter)
            setCurrentIndex(index)
            mGalleryView.requestFill()
        } else {
            mInterval = interval
        }
    }

    private fun resetParameters() {
        mScale = 1.0f
        mOffsetX = 0
        mOffsetY = 0
        mDeltaX = 0
        mDeltaY = 0
        mKeepTopPageIndex = GalleryPageView.INVALID_INDEX
        mKeepTop = INVALID_TOP
        mFirstShownPageIndex = GalleryPageView.INVALID_INDEX
        mScrollUp = false
        mFlingUp = false
        mStopAnimationFinger = false
        mPendingStartFraction = Float.NaN
        mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
    }

    // Return true for animations are running
    private fun cancelAllAnimations(): Boolean {
        val running = mPageFling.isRunning ||
            mSmoothScaler.isRunning ||
            mOverScroller.isRunning
        mPageFling.cancel()
        mSmoothScaler.cancel()
        mOverScroller.cancel()
        return running
    }

    override fun onAttach(adapter: GalleryView.Adapter?) {
        AssertUtils.assertNull("The ScrollLayoutManager is attached", mAdapter)
        AssertUtils.assertNotNull("The iterator is null", adapter)
        mAdapter = adapter
        // Reset parameters
        resetParameters()
    }

    private fun removeProgress() {
        val progress = mProgress
        if (progress != null) {
            mGalleryView.removeComponent(progress)
            mGalleryView.releaseProgress(progress)
            mProgress = null
        }
    }

    private fun removeErrorView() {
        val errorView = mErrorView
        if (errorView != null) {
            mGalleryView.removeComponent(errorView)
            mGalleryView.releaseErrorView(errorView)
            mErrorView = null
            mErrorStr = null
        }
    }

    private fun removePage(page: GalleryPageView) {
        mGalleryView.removeComponent(page)
        mAdapter!!.unbind(page)
        mGalleryView.releasePage(page)
    }

    private fun removeAllPages() {
        for (page in mPages) {
            removePage(page)
        }
        mPages.clear()
    }

    override fun onDetach(): GalleryView.Adapter? {
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", mAdapter)

        // Cancel all animations
        cancelAllAnimations()

        // Remove all view
        removeProgress()
        removeErrorView()
        removeAllPages()

        // Clear iterator
        val iterator = mAdapter
        mAdapter = null

        return iterator
    }

    private fun obtainPage(): GalleryPageView {
        val page = mGalleryView.obtainPage()
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
        val height = mGalleryView.height
        val bound = page.bounds()
        val pageTop = bound.top
        val pageBottom = bound.bottom
        return (pageTop >= 0 && pageTop < height) || (pageBottom > 0 && pageBottom <= height) ||
            (pageTop < 0 && pageBottom > height)
    }

    private fun getReservation(): Float = max(RESERVATION, ((1 + 2 * RESERVATION) * mScale - 1) / 2)

    private fun fillPages(startIndexArg: Int, startOffsetArg: Int) {
        var startIndex = startIndexArg
        var startOffset = startOffsetArg
        val adapter = mAdapter!!
        val galleryView = mGalleryView
        val pages = mPages
        val tempPages = mTempPages
        val width = galleryView.width
        val height = galleryView.height
        val pageWidth = (width * mScale).toInt()
        val size = adapter.size()
        val interval = mInterval
        val reservation = getReservation()
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
            mOffsetX = MathUtils.clamp(mOffsetX, -margin, 0)
        } else {
            mOffsetX = -margin / 2
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
        page.layout(mOffsetX, startOffset, mOffsetX + pageWidth, startOffset + page.measuredHeight)

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
            page.layout(mOffsetX, bottomOffset - page.measuredHeight, mOffsetX + pageWidth, bottomOffset)
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
            page.layout(mOffsetX, topOffset, mOffsetX + pageWidth, topOffset + page.measuredHeight)
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
                page.layout(mOffsetX, bottom - page.measuredHeight, mOffsetX + pageWidth, bottom)
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
            mIndex = page.index
            mOffsetY = page.bounds().top
        }
    }

    override fun onFill() {
        val adapter = mAdapter
        val galleryView = mGalleryView
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", adapter)

        val size = adapter!!.size()
        val error = adapter.getError()

        if (size == GalleryProvider.STATE_WAIT) { // Wait here, show progress bar
            // Remove error view and all pages
            removeErrorView()
            removeAllPages()

            // Ensure progress
            var progress = mProgress
            if (progress == null) {
                progress = galleryView.obtainProgress()
                mProgress = progress
                galleryView.addComponent(progress)
            }

            // Place progress center
            placeCenter(progress)
        } else if (size <= GalleryProvider.STATE_ERROR || size == 0) { // Get error or empty, show error text
            // Ensure error is not null
            val errorStr: String = if (0 == size) {
                galleryView.getEmptyStr()
            } else {
                error ?: galleryView.getDefaultErrorStr()
            }

            // Remove progress and all pages
            removeProgress()
            removeAllPages()

            // Ensure error view
            var errorView = mErrorView
            if (errorView == null) {
                errorView = galleryView.obtainErrorView()
                mErrorView = errorView
                galleryView.addComponent(errorView)
            }

            // Update error string
            if (errorStr != mErrorStr) {
                mErrorStr = errorStr
                galleryView.bindErrorView(errorView, errorStr)
            }

            // Place error view center
            placeCenter(errorView)
        } else {
            val pages = mPages

            // Remove progress and error view
            removeProgress()
            removeErrorView()

            // Ensure index in range
            var index = mIndex
            if (index < 0) {
                Log.e(TAG, "index < 0, index = $index")
                index = 0
                mIndex = index
                removeAllPages()
            } else if (index >= size) {
                Log.e(TAG, "index >= size, index = $index, size = $size")
                index = size - 1
                mIndex = index
                removeAllPages()
            }

            // Find keep index and keep top
            var keepTop = INVALID_TOP
            val keepTopIndex: Int
            if (GalleryPageView.INVALID_INDEX != mKeepTopPageIndex) {
                keepTopIndex = mKeepTopPageIndex
                keepTop = mKeepTop
            } else if (GalleryPageView.INVALID_INDEX != mFirstShownPageIndex) {
                keepTopIndex = mFirstShownPageIndex
            } else {
                keepTopIndex = GalleryPageView.INVALID_INDEX
            }
            if (GalleryPageView.INVALID_INDEX != keepTopIndex && INVALID_TOP == keepTop) {
                keepTop = mOffsetY
                for (page in pages) {
                    // Check keep page
                    if (keepTopIndex == page.index) {
                        break
                    }
                    keepTop += page.height + mInterval
                }
            }

            val startIndex: Int
            val startOffset: Int
            if (GalleryPageView.INVALID_INDEX != keepTopIndex) {
                startIndex = keepTopIndex
                startOffset = keepTop
            } else {
                startIndex = mIndex
                startOffset = mOffsetY
            }
            fillPages(startIndex, startOffset)

            // Get first shown image
            mFirstShownPageIndex = GalleryPageView.INVALID_INDEX
            for (page in mPages) {
                // Check first shown loaded page
                if ((mScrollUp || mFlingUp) && !page.isLoaded) {
                    continue
                }

                if (isInScreen(page)) {
                    mFirstShownPageIndex = page.index
                    mKeepTopPageIndex = page.index
                    break
                }
            }

            // Apply pending intra-page restore via the keep-top
            // anchors (mKeepTopPageIndex / mKeepTop) — the same
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
            if (!mPendingStartFraction.isNaN() &&
                mPendingStartFractionPageIndex != GalleryPageView.INVALID_INDEX
            ) {
                var target: GalleryPageView? = null
                for (p in mPages) {
                    if (p.index == mPendingStartFractionPageIndex) {
                        target = p
                        break
                    }
                }
                if (target != null && target.isLoaded && target.height > 0) {
                    val desiredTop = -Math.round(mPendingStartFraction * target.height)
                    Log.d(
                        TAG,
                        "[FRACTION] applying: page=" + mPendingStartFractionPageIndex +
                            " fraction=" + mPendingStartFraction +
                            " pageHeight=" + target.height +
                            " desiredTop=" + desiredTop,
                    )
                    // Pin the target page at desiredTop via keep-top
                    // anchors. fillPages reads (mKeepTopPageIndex,
                    // mKeepTop) at the start of each fill (line 484)
                    // and uses them as (startIndex, startOffset),
                    // exactly what we want. The pin survives until
                    // onScroll/onFling clear it on user input
                    // (lines ~744, ~756).
                    mKeepTopPageIndex = mPendingStartFractionPageIndex
                    mKeepTop = desiredTop
                    mPendingStartFraction = Float.NaN
                    mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
                    // Force an immediate re-fill within this render
                    // cycle so the new anchor lands on screen now;
                    // mGalleryView.requestFill() would be a no-op
                    // here since we're inside onFill
                    // (mEnableRequestFill == false).
                    mGalleryView.forceFill()
                }
            }
        }
    }

    override fun onDown() {
        mDeltaX = 0
        mDeltaY = 0
        mScrollUp = false
        mStopAnimationFinger = cancelAllAnimations()
        // User touch wins: drop any pending start-fraction restore so we
        // don't snap the page out from under the finger if the target
        // page's image happens to load mid-gesture.
        mPendingStartFraction = Float.NaN
        mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
    }

    override fun onUp() {
        mScrollUp = false
        mGalleryView.getEdgeView().onRelease()
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float) {
        if (mPages.size <= 0) {
            return
        }

        val startScale = mScale
        val endScale: Float
        if (startScale < MAX_SCALE - SCALE_ERROR) {
            endScale = MAX_SCALE
        } else {
            endScale = MIN_SCALE
        }

        // Follows the system animator scale; at 0 the zoom is instant.
        mSmoothScaler.startSmoothScaler(
            x, y, startScale, endScale,
            AnimatorDurationScale.scale(DOUBLE_TAP_SCALE_DURATION).toInt(),
        )
    }

    override fun onLongPress(x: Float, y: Float) = Unit

    fun overScrollEdge(dx: Int, dy: Int, x: Float, y: Float) {
        val edgeView = mGalleryView.getEdgeView()

        mDeltaX += dx
        mDeltaY += dy

        if (mDeltaX < 0) {
            edgeView.onPull((-mDeltaX).toFloat(), y, GLEdgeView.LEFT)
            if (!edgeView.isFinished(GLEdgeView.RIGHT)) {
                edgeView.onRelease(GLEdgeView.RIGHT)
            }
        } else if (mDeltaX > 0) {
            edgeView.onPull(mDeltaX.toFloat(), y, GLEdgeView.RIGHT)
            if (!edgeView.isFinished(GLEdgeView.LEFT)) {
                edgeView.onRelease(GLEdgeView.LEFT)
            }
        }

        if (mDeltaY < 0) {
            edgeView.onPull((-mDeltaY).toFloat(), x, GLEdgeView.TOP)
            if (!edgeView.isFinished(GLEdgeView.BOTTOM)) {
                edgeView.onRelease(GLEdgeView.BOTTOM)
            }
        } else if (mDeltaY > 0) {
            edgeView.onPull(mDeltaY.toFloat(), x, GLEdgeView.BOTTOM)
            if (!edgeView.isFinished(GLEdgeView.TOP)) {
                edgeView.onRelease(GLEdgeView.TOP)
            }
        }
    }

    private fun getBottomState() {
        val pages: List<GalleryPageView> = mPages
        var bottom = mOffsetY
        var i = 0
        for (page in pages) {
            if (i != 0) {
                bottom += mInterval
            }
            bottom += page.height
            i++
        }
        val hasNext = mIndex + pages.size < mAdapter!!.size()

        mBottomStateBottom = bottom
        mBottomStateHasNext = hasNext
    }

    /**
     * Whether the strip is scrolled to (or past) the bottom edge of the
     * last page — the exact condition that gates the BOTTOM over-scroll
     * branch in [onPageRight]. Reusing [getBottomState]
     * out-of-band is safe: it only writes the mBottomState* scratch
     * fields, and every other caller recomputes them immediately before
     * reading. It does dereference mAdapter unconditionally though, so
     * guard the detached/empty cases first.
     *
     * Known acceptable edge: a strip that fully fits on one screen
     * reports true even during a TOP over-scroll, because the last
     * page's bottom is always within the viewport then. Tolerable — the
     * whole archive is visible, so "at the end" is still accurate.
     */
    override fun isReachEnd(): Boolean {
        if (mAdapter == null || mPages.isEmpty() || mAdapter!!.size() <= 0) {
            return false
        }
        getBottomState()
        return !mBottomStateHasNext && mBottomStateBottom <= mGalleryView.height
    }

    override fun collectPageTransforms(out: MutableList<PageTransform>) {
        for (page in mPages) {
            collectPageTransform(out, page)
        }
    }

    // True for get top or bottom
    private fun scrollInternal(dx: Float, dy: Float, fling: Boolean, x: Float, y: Float): Boolean {
        if (mPages.size <= 0) {
            return false
        }

        val galleryView = mGalleryView
        val width = galleryView.width
        val height = galleryView.height
        val pageWidth = (width * mScale).toInt()
        val reservation = getReservation()
        var requestFill = false
        var result = false

        val margin = pageWidth - width
        val dxInt = dx.toInt()
        if (margin > 0 && 0 != dxInt) {
            val oldOffsetX = mOffsetX
            val exceptOffsetX = oldOffsetX - dxInt
            mOffsetX = MathUtils.clamp(exceptOffsetX, -margin, 0)
            if (mOffsetX != oldOffsetX) {
                requestFill = true
            }
            // Do not show over scroll effect for left and right
            /*
            int extraOffsetX = mOffsetX - exceptOffsetX;
            if (0 != extraOffsetX) {
                overScrollEdge(extraOffsetX, 0, x, y);
            }
            */
        }

        var remainY = dy.toInt()
        while (remainY != 0) {
            if (remainY < 0) { // Try to show top
                val limit: Int
                if (mIndex > 0) {
                    limit = (-height * reservation).toInt() + mInterval
                } else {
                    limit = 0
                }

                if (mOffsetY - remainY <= limit) {
                    mOffsetY -= remainY
                    remainY = 0
                    requestFill = true
                    mDeltaX = 0
                    mDeltaY = 0
                } else {
                    if (mIndex > 0) {
                        mOffsetY = limit
                        remainY = remainY + limit - mOffsetY
                        // Offset one pixel to avoid infinite loop
                        ++mOffsetY
                        ++remainY
                        galleryView.forceFill()
                        requestFill = false
                        mDeltaX = 0
                        mDeltaY = 0
                    } else {
                        if (mOffsetY != limit) {
                            mOffsetY = limit
                            requestFill = true
                        }
                        if (!fling) {
                            overScrollEdge(0, remainY + limit - mOffsetY, x, y)
                        }
                        remainY = 0
                        result = true
                    }
                }
            } else { // Try to show bottom
                getBottomState()
                val bottom = mBottomStateBottom
                val hasNext = mBottomStateHasNext

                var limit: Int
                if (hasNext) {
                    limit = (height * (1 + reservation)).toInt() - mInterval
                } else {
                    limit = height
                }
                // Fix limit for page not fill screen
                limit = min(bottom, limit)

                if (bottom - remainY >= limit) {
                    mOffsetY -= remainY
                    remainY = 0
                    requestFill = true
                    mDeltaX = 0
                    mDeltaY = 0
                } else {
                    if (hasNext) {
                        mOffsetY -= bottom - limit
                        remainY = remainY + limit - bottom
                        // Offset one pixel to avoid infinite loop
                        --mOffsetY
                        --remainY
                        galleryView.forceFill()
                        requestFill = false
                        mDeltaX = 0
                        mDeltaY = 0
                    } else {
                        if (mOffsetY != limit) {
                            mOffsetY -= bottom - limit
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
            mGalleryView.requestFill()
        }

        return result
    }

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        mKeepTopPageIndex = GalleryPageView.INVALID_INDEX
        mKeepTop = INVALID_TOP
        mScrollUp = dy < 0
        scrollInternal(dx, dy, false, x, y)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (mPages.size <= 0) {
            return
        }

        mKeepTopPageIndex = GalleryPageView.INVALID_INDEX
        mKeepTop = INVALID_TOP
        mFlingUp = velocityY > 0

        val maxX: Int
        val minX: Int
        val width = mGalleryView.width
        val pageWidth = (width * mScale).toInt()
        val margin = pageWidth - width
        if (margin > 0) {
            maxX = -mOffsetX
            minX = -margin + mOffsetX
        } else {
            maxX = 0
            minX = 0
        }

        val maxY: Int
        if (mIndex > 0) {
            maxY = Int.MAX_VALUE
        } else {
            maxY = -mOffsetY
        }

        getBottomState()
        val bottom = mBottomStateBottom
        val hasNext = mBottomStateHasNext
        val minY: Int
        if (hasNext) {
            minY = Int.MIN_VALUE
        } else {
            minY = mGalleryView.height - bottom
        }

        mPageFling.startFling(
            velocityX.toInt(), minX, maxX,
            velocityY.toInt(), minY, maxY,
        )
    }

    override fun canScale(): Boolean = mPages.size > 0

    override fun onScale(focusX: Float, focusY: Float, scale: Float) {
        if (mPages.isEmpty()) {
            return
        }

        val oldScale = mScale
        mScale = MathUtils.clamp(oldScale * scale, MIN_SCALE, MAX_SCALE)
        val appliedScale = mScale / oldScale

        if (oldScale != mScale) {
            var page: GalleryPageView? = null
            // Keep scale page origin position
            for (p in mPages) {
                if (p.bounds().top < focusY) {
                    page = p
                } else {
                    break
                }
            }

            if (null != page) {
                mKeepTopPageIndex = page.index
                mKeepTop = page.bounds().top

                mGalleryView.forceFill()
                val oldKeepTop = mKeepTop
                mKeepTop = INVALID_TOP

                // Apply scroll
                val newOffsetX = (focusX - ((focusX - mOffsetX) * appliedScale)).toInt()
                val newKeepTop: Int
                if (page.isLoaded) {
                    newKeepTop = (focusY - ((focusY - oldKeepTop) * appliedScale)).toInt()
                } else {
                    newKeepTop = oldKeepTop
                }
                scrollInternal(
                    (mOffsetX - newOffsetX).toFloat(), (oldKeepTop - newKeepTop).toFloat(),
                    false, focusX, focusY,
                )
            } else {
                Log.e(TAG, "Can't find target page")
                mKeepTopPageIndex = GalleryPageView.INVALID_INDEX
                mKeepTop = INVALID_TOP
                mGalleryView.forceFill()
            }
        }
    }

    override fun onUpdateAnimation(time: Long): Boolean {
        var invalidate = mPageFling.calculate(time)
        invalidate = invalidate or mSmoothScaler.calculate(time)
        invalidate = invalidate or mOverScroller.calculate(time)
        return invalidate
    }

    override fun onDataChanged() {
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", mAdapter)

        // Cancel all animations
        cancelAllAnimations()
        // Remove all views
        removeProgress()
        removeErrorView()
        removeAllPages()
        // Reset parameters
        resetParameters()
        mGalleryView.requestFill()
    }

    override fun onPageLeft() {
        val size = mAdapter!!.size()
        if (size <= 0 || mPages.isEmpty()) {
            return
        }

        ///////
        // UP
        ///////
        val galleryView = mGalleryView
        if (mIndex == 0 && mOffsetY >= 0) {
            mOverScroller.overScroll(GLEdgeView.TOP)
            mGalleryView.onTransferEnd()
        } else {
            // Cancel all animations
            cancelAllAnimations()

            // Get first shown page
            var previousPage: GalleryPageView? = null
            var firstShownPage: GalleryPageView? = null
            for (p in mPages) {
                if (isInScreen(p)) {
                    firstShownPage = p
                    break
                }
                previousPage = p
            }

            val height = galleryView.height
            val maxOffset = height - mInterval
            if (null == firstShownPage) {
                Log.e(TAG, "Can't find first shown page when paging left")
                mOffsetY += height / 2
            } else {
                val firstShownTop = firstShownPage.bounds().top
                if (firstShownTop >= 0) {
                    if (null == previousPage) {
                        Log.e(TAG, "Can't find previous page when paging left and offsetY == 0")
                        mOffsetY += height / 2
                    } else {
                        mOffsetY += min(maxOffset, -previousPage.bounds().top)
                    }
                } else {
                    mOffsetY += min(maxOffset, -firstShownTop)
                }
            }

            // Request fill
            mGalleryView.requestFill()
        }
    }

    override fun onPageRight() {
        val size = mAdapter!!.size()
        if (size <= 0 || mPages.isEmpty()) {
            return
        }

        /////////
        // DOWN
        /////////
        val galleryView = mGalleryView
        getBottomState()
        val bottom = mBottomStateBottom
        val hasNext = mBottomStateHasNext
        if (!hasNext && bottom <= galleryView.height) {
            mOverScroller.overScroll(GLEdgeView.BOTTOM)
            mGalleryView.onTransferEnd()
        } else {
            // Cancel all animations
            cancelAllAnimations()

            // Get first shown page
            var lastShownPage: GalleryPageView? = null
            var nextPage: GalleryPageView? = null
            for (p in mPages) {
                if (isInScreen(p)) {
                    lastShownPage = p
                } else if (null != lastShownPage) {
                    nextPage = p
                    break
                }
            }

            val height = galleryView.height
            val maxOffset = height - mInterval
            if (null == lastShownPage) {
                Log.e(TAG, "Can't find last shown page when paging left")
                mOffsetY -= height / 2
            } else {
                val lastShownBottom = lastShownPage.bounds().bottom
                if (lastShownBottom <= height) {
                    if (null == nextPage) {
                        Log.e(TAG, "Can't find previous page when paging left and offsetY == 0")
                        mOffsetY -= height / 2
                    } else {
                        mOffsetY -= min(maxOffset, nextPage.bounds().bottom - height)
                    }
                } else {
                    mOffsetY -= min(maxOffset, lastShownBottom - height)
                }
            }

            // Request fill
            mGalleryView.requestFill()
        }
    }

    override fun isTapOrPressEnable(): Boolean = !mStopAnimationFinger

    override fun findPageByIndex(index: Int): GalleryPageView? {
        for (page in mPages) {
            if (page.index == index) {
                return page
            }
        }
        return null
    }

    override fun getCurrentIndex(): Int {
        for (page in mPages) {
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
            mPendingStartFraction = Float.NaN
            mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
            Log.d(
                TAG,
                "[FRACTION] setStartScrollFraction cleared pending (pageIndex=" +
                    pageIndex + ", fraction=" + fraction + ")",
            )
            return
        }
        val clamped = MathUtils.clamp(fraction, 0f, 1f)
        if (clamped <= 0f) {
            mPendingStartFraction = Float.NaN
            mPendingStartFractionPageIndex = GalleryPageView.INVALID_INDEX
            Log.d(TAG, "[FRACTION] setStartScrollFraction <= 0 -> cleared pending")
            return
        }
        mPendingStartFraction = clamped
        mPendingStartFractionPageIndex = pageIndex
        Log.d(TAG, "[FRACTION] setStartScrollFraction page=" + pageIndex + " fraction=" + clamped)
        mGalleryView.requestFill()
    }

    /**
     * Compute the intra-page scroll fraction for the page that is
     * currently the "first visible" one. Pages that haven't been
     * measured yet, or empty layouts, return 0.
     */
    override fun getCurrentScrollFraction(): Float {
        if (mPages.isEmpty()) {
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
        var size = mAdapter!!.size()
        if (size <= 0) {
            // Can't get size now, assume size is MAX
            size = Int.MAX_VALUE
        }
        if (index < 0 || index >= size) {
            return
        }

        mKeepTopPageIndex = index
        mKeepTop = INVALID_TOP

        if (mPages.isEmpty()) {
            mIndex = index
        } else {
            // Fix the index page
            var targetPage: GalleryPageView? = null
            for (page in mPages) {
                if (page.index == index) {
                    targetPage = page
                    break
                }
            }

            if (targetPage != null) {
                // Cancel all animations
                cancelAllAnimations()
                mOffsetY -= targetPage.bounds().top
                // Request fill
                mGalleryView.requestFill()
            } else {
                mIndex = index
                mOffsetY = 0
                // Cancel all animations
                cancelAllAnimations()
                // Remove all view
                removeProgress()
                removeErrorView()
                removeAllPages()
                // Request fill
                mGalleryView.requestFill()
            }
        }
    }

    override fun getIndexUnder(x: Float, y: Float): Int {
        if (mPages.isEmpty()) {
            return GalleryPageView.INVALID_INDEX
        } else {
            val intX = x.toInt()
            val intY = y.toInt()
            for (page in mPages) {
                if (page.bounds().contains(intX, intY)) {
                    return page.index
                }
            }
            return GalleryPageView.INVALID_INDEX
        }
    }

    override fun getInternalCurrentIndex(): Int {
        var currentIndex = getCurrentIndex()
        if (currentIndex == GalleryPageView.INVALID_INDEX) {
            currentIndex = mIndex
        }
        return currentIndex
    }

    private inner class PageFling(context: Context) : Fling(context) {

        private var mVelocityX = 0
        private var mVelocityY = 0
        private var mDx = 0
        private var mDy = 0
        private var mLastX = 0
        private var mLastY = 0

        fun startFling(
            velocityX: Int,
            minX: Int,
            maxX: Int,
            velocityY: Int,
            minY: Int,
            maxY: Int,
        ) {
            mVelocityX = velocityX
            mVelocityY = velocityY
            mDx = (getSplineFlingDistance(velocityX) * Math.signum(velocityX.toFloat())).toInt()
            mDy = (getSplineFlingDistance(velocityY) * Math.signum(velocityY.toFloat())).toInt()
            mLastX = 0
            mLastY = 0
            var durationX = getSplineFlingDuration(velocityX)
            var durationY = getSplineFlingDuration(velocityY)

            if (mDx < minX) {
                durationX = adjustDuration(0, mDx, minX, durationX)
                mDx = minX
            }
            if (mDx > maxX) {
                durationX = adjustDuration(0, mDx, maxX, durationX)
                mDx = maxX
            }
            if (mDy < minY) {
                durationY = adjustDuration(0, mDy, minY, durationY)
                mDy = minY
            }
            if (mDy > maxY) {
                durationY = adjustDuration(0, mDy, maxY, durationY)
                mDy = maxY
            }

            if (mDx == 0 && mDy == 0) {
                return
            }

            duration = max(durationX, durationY).toLong()
            start()
            mGalleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            val x = (mDx * progress).toInt()
            val y = (mDy * progress).toInt()
            val offsetX = x - mLastX
            val offsetY = y - mLastY
            if (scrollInternal((-offsetX).toFloat(), (-offsetY).toFloat(), true, 0f, 0f)) {
                cancel()
                onFinish()
            }
            mLastX = x
            mLastY = y
        }

        override fun onFinish() {
            mFlingUp = false

            val index = mIndex

            val topEdge = index <= 0 && mOffsetY >= 0

            getBottomState()
            val bottom = mBottomStateBottom
            val hasNext = mBottomStateHasNext
            val bottomEdge = !hasNext && bottom <= mGalleryView.height

            if (topEdge && bottomEdge) {
                return
            }

            val edgeView = mGalleryView.getEdgeView()
            if (topEdge && edgeView.isFinished(GLEdgeView.TOP)) {
                edgeView.onAbsorb(mVelocityY, GLEdgeView.TOP)
            } else if (bottomEdge && edgeView.isFinished(GLEdgeView.BOTTOM)) {
                edgeView.onAbsorb(-mVelocityY, GLEdgeView.BOTTOM)
            }
        }
    }

    private inner class SmoothScaler : Animation() {

        private var mFocusX = 0f
        private var mFocusY = 0f
        private var mStartScale = 0f
        private var mEndScale = 0f
        private var mLastScale = 0f

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
            mFocusX = focusX
            mFocusY = focusY
            mStartScale = startScale
            mEndScale = endScale
            mLastScale = startScale
            this.duration = duration.toLong()
            start()
            mGalleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            if (mPages.size <= 0) {
                return
            }

            val scale = MathUtils.lerp(mStartScale, mEndScale, progress)
            onScale(mFocusX, mFocusY, scale / mLastScale)
            mLastScale = scale
        }
    }

    private inner class OverScroller : FloatAnimation() {

        private var mDirection = 0
        private var mPosition = 0

        init {
            duration = 300L
        }

        fun overScroll(direction: Int) {
            mDirection = direction
            val range: Int
            when (mDirection) {
                GLEdgeView.LEFT, GLEdgeView.RIGHT -> {
                    range = mGalleryView.width / 7
                    mPosition = mGalleryView.height / 2
                }
                GLEdgeView.TOP, GLEdgeView.BOTTOM -> {
                    range = mGalleryView.height / 7
                    mPosition = mGalleryView.width / 2
                }
                else -> return
            }
            setRange(0f, range.toFloat())
            start()
            mGalleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            super.onCalculate(progress)
            mGalleryView.getEdgeView().onPull(get(), mPosition.toFloat(), mDirection)
        }

        override fun onFinish() {
            mGalleryView.getEdgeView().onRelease(mDirection)
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
