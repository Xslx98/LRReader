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
    "ComplexCondition",
    "LargeClass",
)

package com.lanraragi.framework.lib.glgallery

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.animation.Interpolator
import androidx.annotation.IntDef
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
import kotlin.math.abs
import kotlin.math.max

internal class PagerLayoutManager(
    context: Context,
    galleryView: GalleryView,
    private var mScaleMode: Int,
    private var mStartPosition: Int,
    private var mScaleValue: Float,
    private var mInterval: Int,
) : GalleryView.LayoutManager(galleryView) {

    @IntDef(MODE_LEFT_TO_RIGHT, MODE_RIGHT_TO_LEFT)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Mode

    private var mAdapter: GalleryView.Adapter? = null

    private var mProgress: GLProgressView? = null
    private var mErrorStr: String? = null
    private var mErrorView: GLTextureView? = null
    private var mPrevious: GalleryPageView? = null
    private var mCurrent: GalleryPageView? = null
    private var mNext: GalleryPageView? = null

    @Mode
    private var mMode = MODE_RIGHT_TO_LEFT

    private var mOffset = 0
    private var mDeltaX = 0
    private var mDeltaY = 0
    private var mCanScrollBetweenPages = false
    private var mStopAnimationFinger = false

    private val mScrollRemain = IntArray(2)
    private val mScaleDefault = FloatArray(4)

    private val mSmoothScroller: SmoothScroller = SmoothScroller()
    private val mPageFling: PageFling = PageFling(context)
    private val mSmoothScaler: SmoothScaler = SmoothScaler()
    private val mOverScroller: OverScroller = OverScroller()

    // Current index
    private var mIndex = 0

    private val mTempRect = Rect()

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
        mOffset = 0
        mDeltaX = 0
        mDeltaY = 0
        mCanScrollBetweenPages = false
        mStopAnimationFinger = false
    }

    private fun cancelAllAnimations(): Boolean {
        val running = mSmoothScroller.isRunning ||
            mPageFling.isRunning ||
            mSmoothScaler.isRunning ||
            mOverScroller.isRunning
        mSmoothScroller.cancel()
        mPageFling.cancel()
        mSmoothScaler.cancel()
        mOverScroller.cancel()
        return running
    }

    fun setMode(@Mode mode: Int) {
        if (mMode == mode) {
            return
        }

        mMode = mode
        if (mAdapter != null) {
            // It is attached, refill
            // Cancel all animations
            cancelAllAnimations()
            // Remove all view
            removeProgress()
            removeErrorView()
            removeAllPages()
            // Reset parameters
            resetParameters()
            // Request fill
            mGalleryView.requestFill()
        }
    }

    fun setScaleMode(scaleMode: Int) {
        if (mScaleMode == scaleMode) {
            return
        }
        mScaleMode = scaleMode

        val current = mCurrent
        if (current != null) {
            current.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        val previous = mPrevious
        if (previous != null) {
            previous.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        val next = mNext
        if (next != null) {
            next.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
    }

    fun setStartPosition(startPosition: Int) {
        if (mStartPosition == startPosition) {
            return
        }
        mStartPosition = startPosition

        val current = mCurrent
        if (current != null) {
            current.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        val previous = mPrevious
        if (previous != null) {
            previous.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        val next = mNext
        if (next != null) {
            next.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
    }

    override fun onAttach(adapter: GalleryView.Adapter?) {
        AssertUtils.assertNull("The PagerLayoutManager is attached", mAdapter)
        AssertUtils.assertNotNull("The adapter is null", adapter)
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
        // Remove gallery view
        val previous = mPrevious
        if (previous != null) {
            removePage(previous)
            mPrevious = null
        }
        val current = mCurrent
        if (current != null) {
            removePage(current)
            mCurrent = null
        }
        val next = mNext
        if (next != null) {
            removePage(next)
            mNext = null
        }
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
        val adapter = mAdapter
        mAdapter = null

        return adapter
    }

    private fun getLeftPage(): GalleryPageView? = when (mMode) {
        MODE_LEFT_TO_RIGHT -> mPrevious
        else -> mNext
    }

    private fun getRightPage(): GalleryPageView? = when (mMode) {
        MODE_LEFT_TO_RIGHT -> mNext
        else -> mPrevious
    }

    private fun obtainPage(): GalleryPageView {
        val page = mGalleryView.obtainPage()
        page.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        return page
    }

    private fun layoutPage(
        page: GalleryPageView,
        widthSpec: Int,
        heightSpec: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val rect = mTempRect
        page.getValidRect(rect)
        val oldValid = !rect.isEmpty
        page.measure(widthSpec, heightSpec)
        page.layout(left, top, right, bottom)
        page.getValidRect(rect)
        val newValid = !rect.isEmpty
        if (!oldValid && newValid) {
            page.imageView.setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
    }

    override fun onFill() {
        val adapter = mAdapter
        val galleryView = mGalleryView
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", adapter)

        val width = galleryView.width
        val height = galleryView.height
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
            // Remove progress and error view
            removeProgress()
            removeErrorView()

            // Ensure index in range
            var index = mIndex
            if (index < 0) {
                index = 0
                mIndex = index
                removeAllPages()
                Log.e(TAG, "index < 0, index = $index")
            } else if (index >= size) {
                index = size - 1
                mIndex = index
                removeAllPages()
                Log.e(TAG, "index >= size, index = $index, size = $size")
            }

            // Ensure pages
            var current = mCurrent
            if (current == null) {
                current = obtainPage()
                mCurrent = current
                galleryView.addComponent(current)
                adapter.bind(current, index)
            }
            val previous = mPrevious
            if (previous == null && index > 0) {
                val page = obtainPage()
                mPrevious = page
                galleryView.addComponent(page)
                adapter.bind(page, index - 1)
            } else if (previous != null && index == 0) {
                removePage(previous)
                mPrevious = null
            }
            val next = mNext
            if (next == null && index < size - 1) {
                val page = obtainPage()
                mNext = page
                galleryView.addComponent(page)
                adapter.bind(page, index + 1)
            } else if (next != null && index == size - 1) {
                removePage(next)
                mNext = null
            }

            val leftPage = getLeftPage()
            val rightPage = getRightPage()

            // Fix offset
            val min = if (rightPage == null) 0 else -width - mInterval + 1
            val max = if (leftPage == null) 0 else width + mInterval - 1
            mOffset = MathUtils.clamp(mOffset, min, max)

            // Measure and layout pages
            val offset = mOffset
            val widthSpec = GLView.MeasureSpec.makeMeasureSpec(width, GLView.MeasureSpec.EXACTLY)
            val heightSpec = GLView.MeasureSpec.makeMeasureSpec(height, GLView.MeasureSpec.EXACTLY)
            layoutPage(
                current, widthSpec, heightSpec,
                offset, 0, width + offset, height,
            )
            if (leftPage != null) {
                layoutPage(
                    leftPage, widthSpec, heightSpec,
                    -mInterval - width + offset, 0, -mInterval + offset, height,
                )
            }
            if (rightPage != null) {
                layoutPage(
                    rightPage, widthSpec, heightSpec,
                    width + mInterval + offset, 0, width + mInterval + width + offset, height,
                )
            }
        }
    }

    override fun onDown() {
        mDeltaX = 0
        mDeltaY = 0
        mStopAnimationFinger = cancelAllAnimations()
    }

    override fun onUp() {
        mGalleryView.getEdgeView().onRelease()

        if (mCurrent == null) {
            return
        }

        // A drag or fling that ends pulling the forward edge on the last page
        // (mNext == null) is a page-turn attempt past the end, just like the
        // boundary branches in onPageLeft()/onPageRight(). mDeltaX is only
        // non-zero when the gesture ended in overScrollEdge(), and its sign
        // tells which edge was pulled. Note mDeltaX reflects only the FINAL
        // motion event's residual — the image-scroll branch in scrollInternal()
        // re-zeroes it on every event while mOffset == 0 — which is what keeps
        // a pull-then-retreat gesture from firing here. mCanScrollBetweenPages
        // (horizontal-dominant gesture, set on first scroll) filters out
        // vertical-dominant drags whose last event drifts a few pixels
        // horizontally into the edge branch.
        if (mOffset == 0 && mNext == null && mCanScrollBetweenPages &&
            ((mMode == MODE_LEFT_TO_RIGHT && mDeltaX > 0) ||
                (mMode == MODE_RIGHT_TO_LEFT && mDeltaX < 0))
        ) {
            mGalleryView.onTransferEnd()
        }

        // Scroll
        if (mOffset != 0) {
            val width = mGalleryView.width
            val dx: Int
            if (mOffset >= mInterval && getLeftPage() != null) {
                dx = mOffset - width - mInterval
            } else if (mOffset <= -mInterval && getRightPage() != null) {
                dx = mOffset + width + mInterval
            } else {
                dx = mOffset
            }
            val pageDelta = 7 * abs(mOffset).toFloat() / (width + mInterval)
            val duration = ((pageDelta + 1) * 100).toInt()
            // The settle after a drag follows the system animator scale;
            // at 0 it snaps. The user-driven drag and fling are unscaled.
            mSmoothScroller.startSmoothScroll(dx, 0, AnimatorDurationScale.scale(duration.toLong()).toInt())
        }
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float) {
        val current = mCurrent
        if (current == null || !current.imageView.isLoaded) {
            return
        }

        val scales = mScaleDefault
        val image = current.imageView
        image.getScaleDefault(scales)
        val scale = image.scale
        var endScale = scales[0]
        for (value in scales) {
            if (scale < value - 0.01f) {
                endScale = value
                break
            }
        }

        mSmoothScaler.startSmoothScaler(
            x, y, scale, endScale,
            AnimatorDurationScale.scale(DOUBLE_TAP_SCALE_DURATION).toInt(),
        )
    }

    override fun onLongPress(x: Float, y: Float) = Unit

    private fun pagePrevious() {
        if (mIndex <= 0) {
            return
        }
        mIndex--

        val next = mNext
        if (next != null) {
            removePage(next)
        }
        mNext = mCurrent
        mCurrent = mPrevious
        mPrevious = null

        if (mIndex > 0) {
            val page = obtainPage()
            mPrevious = page
            mGalleryView.addComponent(page)
            mAdapter!!.bind(page, mIndex - 1)
        }
    }

    private fun pageNext() {
        val adapter = mAdapter
        val size = adapter!!.size()
        if (mIndex >= size - 1) {
            return
        }
        mIndex++

        val previous = mPrevious
        if (previous != null) {
            removePage(previous)
        }
        mPrevious = mCurrent
        mCurrent = mNext
        mNext = null

        if (mIndex < size - 1) {
            val page = obtainPage()
            mNext = page
            mGalleryView.addComponent(page)
            adapter.bind(page, mIndex + 1)
        }
    }

    private fun pageLeft() {
        when (mMode) {
            MODE_LEFT_TO_RIGHT -> pagePrevious()
            else -> pageNext()
        }
    }

    private fun pageRight() {
        when (mMode) {
            MODE_LEFT_TO_RIGHT -> pageNext()
            else -> pagePrevious()
        }
    }

    private fun scrollBetweenPages(dx: Int): Int {
        val leftPage = getLeftPage()
        val rightPage = getRightPage()
        val width = mGalleryView.width

        val remain: Int
        if (dx < 0) { // Try to show left
            val limit: Int
            if (leftPage == null) {
                limit = 0
            } else {
                limit = width + mInterval
            }

            if (dx > mOffset - limit) {
                remain = 0
                mOffset -= dx
            } else {
                // Go to left page if left page not null
                if (leftPage != null) {
                    pageLeft()
                }
                remain = dx + limit - mOffset
                mOffset = 0
            }
        } else { // Try to show right
            val limit: Int
            if (rightPage == null) {
                limit = 0
            } else {
                limit = -width - mInterval
            }

            if (dx < mOffset - limit) {
                remain = 0
                mOffset -= dx
            } else {
                // Go to right page if right page not null
                if (rightPage != null) {
                    pageRight()
                }
                remain = dx + limit - mOffset
                mOffset = 0
            }
        }

        return remain
    }

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

        if (mCurrent!!.imageView.canFlingVertically()) {
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
    }

    fun scrollInternal(dx: Float, dy: Float, x: Float, y: Float) {
        if (mCurrent == null) {
            return
        }

        var needFill = false
        var canImageScroll = true
        var remainX = dx.toInt()
        var remainY = dy.toInt()

        if (mGalleryView.isFirstScroll()) {
            mCanScrollBetweenPages = abs(dx) > abs(dy) * 1.5
        }

        while (remainX != 0 || remainY != 0) {
            if (mOffset == 0 && canImageScroll) {
                val image = mCurrent!!.imageView
                image.scroll(remainX, remainY, mScrollRemain)
                remainX = mScrollRemain[0]
                remainY = mScrollRemain[1]
                canImageScroll = false
                mDeltaX = 0
                mDeltaY = 0
            } else if (remainX == 0 ||
                (getLeftPage() == null && mOffset == 0 && remainX < 0) ||
                (getRightPage() == null && mOffset == 0 && remainX > 0)
            ) {
                // On edge
                overScrollEdge(remainX, remainY, x, y)
                remainX = 0
                remainY = 0
            } else if (mCanScrollBetweenPages) {
                remainX = scrollBetweenPages(remainX)
                canImageScroll = true
                needFill = true
                mDeltaX = 0
                mDeltaY = 0
            } else {
                remainX = 0
                remainY = 0
                mDeltaX = 0
                mDeltaY = 0
            }
        }

        if (needFill) {
            mGalleryView.requestFill()
        }
    }

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        scrollInternal(dx, dy, x, y)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val current = mCurrent
        if (current == null || mOffset != 0 || !current.imageView.isLoaded ||
            !current.imageView.canFling()
        ) {
            return
        }

        val image = current.imageView
        mPageFling.startFling(
            velocityX.toInt(), image.getMinDx(), image.getMaxDx(),
            velocityY.toInt(), image.getMinDy(), image.getMaxDy(),
        )
    }

    override fun canScale(): Boolean {
        val current = mCurrent
        return current != null && mOffset == 0 && current.imageView.isLoaded
    }

    override fun onScale(focusX: Float, focusY: Float, scale: Float) {
        val current = mCurrent
        if (current == null || !current.imageView.isLoaded) {
            return
        }

        current.imageView.scale(focusX, focusY, scale)
        mScaleValue = current.imageView.scale
    }

    override fun onUpdateAnimation(time: Long): Boolean {
        var invalidate = mSmoothScroller.calculate(time)
        invalidate = invalidate or mPageFling.calculate(time)
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
        if (size <= 0 || mCurrent == null) {
            return
        }

        if (mMode == MODE_LEFT_TO_RIGHT) {
            if (mIndex == 0) {
                mOverScroller.overScroll(GLEdgeView.LEFT)
            } else {
                setCurrentIndex(mIndex - 1)
            }
        } else {
            if (mIndex >= size - 1) {
                mOverScroller.overScroll(GLEdgeView.LEFT)
                mGalleryView.onTransferEnd()
            } else {
                setCurrentIndex(mIndex + 1)
            }
        }
    }

    override fun onPageRight() {
        val size = mAdapter!!.size()
        if (size <= 0 || mCurrent == null) {
            return
        }

        if (mMode == MODE_LEFT_TO_RIGHT) {
            if (mIndex >= size - 1) {
                mOverScroller.overScroll(GLEdgeView.RIGHT)
                mGalleryView.onTransferEnd()
            } else {
                setCurrentIndex(mIndex + 1)
            }
        } else {
            if (mIndex == 0) {
                mOverScroller.overScroll(GLEdgeView.RIGHT)
            } else {
                setCurrentIndex(mIndex - 1)
            }
        }
    }

    override fun isTapOrPressEnable(): Boolean = !mStopAnimationFinger

    override fun findPageByIndex(index: Int): GalleryPageView? {
        val current = mCurrent
        if (current != null && current.index == index) {
            return current
        }
        val previous = mPrevious
        if (previous != null && previous.index == index) {
            return previous
        }
        val next = mNext
        if (next != null && next.index == index) {
            return next
        }
        return null
    }

    override fun getCurrentIndex(): Int {
        val current = mCurrent
        if (current != null) {
            return current.index
        } else {
            return GalleryPageView.INVALID_INDEX
        }
    }

    override fun isReachEnd(): Boolean {
        val adapter = mAdapter
        if (mCurrent == null || adapter == null) {
            return false
        }
        val size = adapter.size()
        // size <= 0 means empty or not-yet-known: no meaningful end.
        return size > 0 && mIndex >= size - 1
    }

    override fun collectPageTransforms(out: MutableList<PageTransform>) {
        collectPageTransform(out, mPrevious)
        collectPageTransform(out, mCurrent)
        collectPageTransform(out, mNext)
    }

    override fun setCurrentIndex(index: Int) {
        var size = mAdapter!!.size()
        if (size <= 0) {
            // Can't get size now, assume size is MAX
            size = Int.MAX_VALUE
        }
        if (index == mIndex || index < 0 || index >= size) {
            return
        }
        if (mCurrent == null) {
            mIndex = index
        } else if (index == mIndex - 1) {
            // Cancel all animations
            cancelAllAnimations()
            // Reset parameters
            resetParameters()
            // Go to previous
            pagePrevious()
            // Request fill
            mGalleryView.requestFill()
        } else if (index == mIndex + 1) {
            // Cancel all animations
            cancelAllAnimations()
            // Reset parameters
            resetParameters()
            // Go to next
            pageNext()
            // Request fill
            mGalleryView.requestFill()
        } else {
            mIndex = index
            // It is attached, refill
            // Cancel all animations
            cancelAllAnimations()
            // Remove all view
            removeProgress()
            removeErrorView()
            removeAllPages()
            // Reset parameters
            resetParameters()
            // Request fill
            mGalleryView.requestFill()
        }
    }

    override fun getIndexUnder(x: Float, y: Float): Int {
        val current = mCurrent
        if (current == null) {
            return GalleryPageView.INVALID_INDEX
        } else {
            val intX = x.toInt()
            val intY = y.toInt()
            val previous = mPrevious
            val next = mNext
            if (current.bounds().contains(intX, intY)) {
                return current.index
            } else if (previous != null && previous.bounds().contains(intX, intY)) {
                return previous.index
            } else if (next != null && next.bounds().contains(intX, intY)) {
                return next.index
            } else {
                return GalleryPageView.INVALID_INDEX
            }
        }
    }

    override fun getInternalCurrentIndex(): Int {
        var currentIndex = getCurrentIndex()
        if (currentIndex == GalleryPageView.INVALID_INDEX) {
            currentIndex = mIndex
        }
        return currentIndex
    }

    // Java's anonymous PagerLayoutManager$1 (quintic ease-out).
    private object SmoothScrollerInterpolator : Interpolator {
        override fun getInterpolation(input: Float): Float {
            val t = input - 1.0f
            return t * t * t * t * t + 1.0f
        }
    }

    private inner class SmoothScroller : Animation() {

        private var mDx = 0
        private var mDy = 0
        private var mLastX = 0
        private var mLastY = 0

        init {
            interpolator = SmoothScrollerInterpolator
        }

        fun startSmoothScroll(dx: Int, dy: Int, duration: Int) {
            mDx = dx
            mDy = dy
            mLastX = 0
            mLastY = 0
            this.duration = duration.toLong()
            start()
            mGalleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            val x = (mDx * progress).toInt()
            val y = (mDy * progress).toInt()
            var offsetX = x - mLastX
            while (offsetX != 0) {
                val oldOffsetX = offsetX
                offsetX = scrollBetweenPages(offsetX)
                // Avoid loop infinitely
                if (offsetX == oldOffsetX) {
                    break
                } else {
                    mGalleryView.requestFill()
                }
            }
            mLastX = x
            mLastY = y
        }
    }

    private inner class PageFling(context: Context) : Fling(context) {

        private var mVelocityX = 0
        private var mVelocityY = 0
        private var mDx = 0
        private var mDy = 0
        private var mLastX = 0
        private var mLastY = 0
        private val mTemp = IntArray(2)

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
            val current = mCurrent
            if (current != null && (offsetX != 0 || offsetY != 0)) {
                current.imageView.scroll(-offsetX, -offsetY, mTemp)
            }
            mLastX = x
            mLastY = y
        }

        override fun onFinish() {
            val current = mCurrent ?: return

            val edgeView = mGalleryView.getEdgeView()
            val imageView = current.imageView
            if (imageView.canFlingHorizontally()) {
                if (mVelocityX > 0 && getLeftPage() == null && imageView.getMaxDx() == 0 &&
                    edgeView.isFinished(GLEdgeView.LEFT)
                ) {
                    edgeView.onAbsorb(mVelocityX, GLEdgeView.LEFT)
                } else if (mVelocityX < 0 && getRightPage() == null && imageView.getMinDx() == 0 &&
                    edgeView.isFinished(GLEdgeView.RIGHT)
                ) {
                    edgeView.onAbsorb(-mVelocityX, GLEdgeView.RIGHT)
                }
            }
            if (imageView.canFlingVertically()) {
                if (mVelocityY > 0 && imageView.getMaxDy() == 0 &&
                    edgeView.isFinished(GLEdgeView.TOP)
                ) {
                    edgeView.onAbsorb(mVelocityY, GLEdgeView.TOP)
                } else if (mVelocityY < 0 && imageView.getMinDy() == 0 &&
                    edgeView.isFinished(GLEdgeView.BOTTOM)
                ) {
                    edgeView.onAbsorb(-mVelocityY, GLEdgeView.BOTTOM)
                }
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
            val current = mCurrent ?: return

            val scale = MathUtils.lerp(mStartScale, mEndScale, progress)
            current.imageView.scale(mFocusX, mFocusY, scale / mLastScale)
            mLastScale = scale
            mScaleValue = scale
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
        private val TAG: String = PagerLayoutManager::class.java.simpleName

        const val MODE_LEFT_TO_RIGHT = 0
        const val MODE_RIGHT_TO_LEFT = 1

        private const val DOUBLE_TAP_SCALE_DURATION = 300L
    }
}
