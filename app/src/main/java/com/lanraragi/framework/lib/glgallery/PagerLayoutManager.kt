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
    private var scaleMode: Int,
    private var startPosition: Int,
    private var scaleValue: Float,
    private var interval: Int,
) : GalleryView.LayoutManager(galleryView) {

    @IntDef(MODE_LEFT_TO_RIGHT, MODE_RIGHT_TO_LEFT)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Mode

    private var adapter: GalleryView.Adapter? = null

    private var progress: GLProgressView? = null
    private var errorStr: String? = null
    private var errorView: GLTextureView? = null
    private var previous: GalleryPageView? = null
    private var current: GalleryPageView? = null
    private var next: GalleryPageView? = null

    @Mode
    private var mode = MODE_RIGHT_TO_LEFT

    private var offset = 0
    private var deltaX = 0
    private var deltaY = 0
    private var canScrollBetweenPages = false
    private var stopAnimationFinger = false

    private val scrollRemain = IntArray(2)
    private val scaleDefault = FloatArray(4)

    private val smoothScroller: SmoothScroller = SmoothScroller()
    private val pageFling: PageFling = PageFling(context)
    private val smoothScaler: SmoothScaler = SmoothScaler()
    private val overScroller: OverScroller = OverScroller()

    // Current index
    private var index = 0

    private val tempRect = Rect()

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
        offset = 0
        deltaX = 0
        deltaY = 0
        canScrollBetweenPages = false
        stopAnimationFinger = false
    }

    private fun cancelAllAnimations(): Boolean {
        val running = smoothScroller.isRunning ||
            pageFling.isRunning ||
            smoothScaler.isRunning ||
            overScroller.isRunning
        smoothScroller.cancel()
        pageFling.cancel()
        smoothScaler.cancel()
        overScroller.cancel()
        return running
    }

    fun setMode(@Mode mode: Int) {
        if (this.mode == mode) {
            return
        }

        this.mode = mode
        if (adapter != null) {
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
            galleryView.requestFill()
        }
    }

    fun setScaleMode(scaleMode: Int) {
        if (this.scaleMode == scaleMode) {
            return
        }
        this.scaleMode = scaleMode

        val current = this.current
        if (current != null) {
            current.imageView.setScaleOffset(this.scaleMode, startPosition, scaleValue)
        }
        val previous = this.previous
        if (previous != null) {
            previous.imageView.setScaleOffset(this.scaleMode, startPosition, scaleValue)
        }
        val next = this.next
        if (next != null) {
            next.imageView.setScaleOffset(this.scaleMode, startPosition, scaleValue)
        }
    }

    fun setStartPosition(startPosition: Int) {
        if (this.startPosition == startPosition) {
            return
        }
        this.startPosition = startPosition

        val current = this.current
        if (current != null) {
            current.imageView.setScaleOffset(scaleMode, this.startPosition, scaleValue)
        }
        val previous = this.previous
        if (previous != null) {
            previous.imageView.setScaleOffset(scaleMode, this.startPosition, scaleValue)
        }
        val next = this.next
        if (next != null) {
            next.imageView.setScaleOffset(scaleMode, this.startPosition, scaleValue)
        }
    }

    override fun onAttach(adapter: GalleryView.Adapter?) {
        AssertUtils.assertNull("The PagerLayoutManager is attached", this.adapter)
        AssertUtils.assertNotNull("The adapter is null", adapter)
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
        // Remove gallery view
        val previous = this.previous
        if (previous != null) {
            removePage(previous)
            this.previous = null
        }
        val current = this.current
        if (current != null) {
            removePage(current)
            this.current = null
        }
        val next = this.next
        if (next != null) {
            removePage(next)
            this.next = null
        }
    }

    override fun onDetach(): GalleryView.Adapter? {
        AssertUtils.assertNotNull("The PagerLayoutManager is not attached", this.adapter)

        // Cancel all animations
        cancelAllAnimations()

        // Remove all view
        removeProgress()
        removeErrorView()
        removeAllPages()

        // Clear iterator
        val adapter = this.adapter
        this.adapter = null

        return adapter
    }

    private val leftPage: GalleryPageView?
        get() = when (mode) {
            MODE_LEFT_TO_RIGHT -> previous
            else -> next
        }

    private val rightPage: GalleryPageView?
        get() = when (mode) {
            MODE_LEFT_TO_RIGHT -> next
            else -> previous
        }

    private fun obtainPage(): GalleryPageView {
        val page = galleryView.obtainPage()
        page.imageView.setScaleOffset(scaleMode, startPosition, scaleValue)
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
        val rect = tempRect
        page.getValidRect(rect)
        val oldValid = !rect.isEmpty
        page.measure(widthSpec, heightSpec)
        page.layout(left, top, right, bottom)
        page.getValidRect(rect)
        val newValid = !rect.isEmpty
        if (!oldValid && newValid) {
            page.imageView.setScaleOffset(scaleMode, startPosition, scaleValue)
        }
    }

    override fun onFill() {
        val adapter = this.adapter
        val galleryView = this.galleryView
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
            // Remove progress and error view
            removeProgress()
            removeErrorView()

            // Ensure index in range
            var index = this.index
            if (index < 0) {
                index = 0
                this.index = index
                removeAllPages()
                Log.e(TAG, "index < 0, index = $index")
            } else if (index >= size) {
                index = size - 1
                this.index = index
                removeAllPages()
                Log.e(TAG, "index >= size, index = $index, size = $size")
            }

            // Ensure pages
            var current = this.current
            if (current == null) {
                current = obtainPage()
                this.current = current
                galleryView.addComponent(current)
                adapter.bind(current, index)
            }
            val previous = this.previous
            if (previous == null && index > 0) {
                val page = obtainPage()
                this.previous = page
                galleryView.addComponent(page)
                adapter.bind(page, index - 1)
            } else if (previous != null && index == 0) {
                removePage(previous)
                this.previous = null
            }
            val next = this.next
            if (next == null && index < size - 1) {
                val page = obtainPage()
                this.next = page
                galleryView.addComponent(page)
                adapter.bind(page, index + 1)
            } else if (next != null && index == size - 1) {
                removePage(next)
                this.next = null
            }

            val leftPage = this.leftPage
            val rightPage = this.rightPage

            // Fix offset
            val min = if (rightPage == null) 0 else -width - interval + 1
            val max = if (leftPage == null) 0 else width + interval - 1
            this.offset = MathUtils.clamp(this.offset, min, max)

            // Measure and layout pages
            val offset = this.offset
            val widthSpec = GLView.MeasureSpec.makeMeasureSpec(width, GLView.MeasureSpec.EXACTLY)
            val heightSpec = GLView.MeasureSpec.makeMeasureSpec(height, GLView.MeasureSpec.EXACTLY)
            layoutPage(
                current, widthSpec, heightSpec,
                offset, 0, width + offset, height,
            )
            if (leftPage != null) {
                layoutPage(
                    leftPage, widthSpec, heightSpec,
                    -interval - width + offset, 0, -interval + offset, height,
                )
            }
            if (rightPage != null) {
                layoutPage(
                    rightPage, widthSpec, heightSpec,
                    width + interval + offset, 0, width + interval + width + offset, height,
                )
            }
        }
    }

    override fun onDown() {
        deltaX = 0
        deltaY = 0
        stopAnimationFinger = cancelAllAnimations()
    }

    override fun onUp() {
        galleryView.edgeView.onRelease()

        if (current == null) {
            return
        }

        // A drag or fling that ends pulling the forward edge on the last page
        // (next == null) is a page-turn attempt past the end, just like the
        // boundary branches in onPageLeft()/onPageRight(). deltaX is only
        // non-zero when the gesture ended in overScrollEdge(), and its sign
        // tells which edge was pulled. Note deltaX reflects only the FINAL
        // motion event's residual — the image-scroll branch in scrollInternal()
        // re-zeroes it on every event while offset == 0 — which is what keeps
        // a pull-then-retreat gesture from firing here. canScrollBetweenPages
        // (horizontal-dominant gesture, set on first scroll) filters out
        // vertical-dominant drags whose last event drifts a few pixels
        // horizontally into the edge branch.
        if (offset == 0 && next == null && canScrollBetweenPages &&
            ((mode == MODE_LEFT_TO_RIGHT && deltaX > 0) ||
                (mode == MODE_RIGHT_TO_LEFT && deltaX < 0))
        ) {
            galleryView.onTransferEnd()
        }

        // Scroll
        if (offset != 0) {
            val width = galleryView.width
            val dx: Int
            if (offset >= interval && leftPage != null) {
                dx = offset - width - interval
            } else if (offset <= -interval && rightPage != null) {
                dx = offset + width + interval
            } else {
                dx = offset
            }
            val pageDelta = 7 * abs(offset).toFloat() / (width + interval)
            val duration = ((pageDelta + 1) * 100).toInt()
            // The settle after a drag follows the system animator scale;
            // at 0 it snaps. The user-driven drag and fling are unscaled.
            smoothScroller.startSmoothScroll(dx, 0, AnimatorDurationScale.scale(duration.toLong()).toInt())
        }
    }

    override fun onDoubleTapConfirmed(x: Float, y: Float) {
        val current = this.current
        if (current == null || !current.imageView.isLoaded) {
            return
        }

        val scales = scaleDefault
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

        smoothScaler.startSmoothScaler(
            x, y, scale, endScale,
            AnimatorDurationScale.scale(DOUBLE_TAP_SCALE_DURATION).toInt(),
        )
    }

    private fun pagePrevious() {
        if (index <= 0) {
            return
        }
        index--

        val next = this.next
        if (next != null) {
            removePage(next)
        }
        this.next = current
        current = previous
        previous = null

        if (index > 0) {
            val page = obtainPage()
            previous = page
            galleryView.addComponent(page)
            adapter!!.bind(page, index - 1)
        }
    }

    private fun pageNext() {
        val adapter = this.adapter
        val size = adapter!!.size()
        if (index >= size - 1) {
            return
        }
        index++

        val previous = this.previous
        if (previous != null) {
            removePage(previous)
        }
        this.previous = current
        current = next
        next = null

        if (index < size - 1) {
            val page = obtainPage()
            next = page
            galleryView.addComponent(page)
            adapter.bind(page, index + 1)
        }
    }

    private fun pageLeft() {
        when (mode) {
            MODE_LEFT_TO_RIGHT -> pagePrevious()
            else -> pageNext()
        }
    }

    private fun pageRight() {
        when (mode) {
            MODE_LEFT_TO_RIGHT -> pageNext()
            else -> pagePrevious()
        }
    }

    private fun scrollBetweenPages(dx: Int): Int {
        val leftPage = this.leftPage
        val rightPage = this.rightPage
        val width = galleryView.width

        val remain: Int
        if (dx < 0) { // Try to show left
            val limit: Int
            if (leftPage == null) {
                limit = 0
            } else {
                limit = width + interval
            }

            if (dx > offset - limit) {
                remain = 0
                offset -= dx
            } else {
                // Go to left page if left page not null
                if (leftPage != null) {
                    pageLeft()
                }
                remain = dx + limit - offset
                offset = 0
            }
        } else { // Try to show right
            val limit: Int
            if (rightPage == null) {
                limit = 0
            } else {
                limit = -width - interval
            }

            if (dx < offset - limit) {
                remain = 0
                offset -= dx
            } else {
                // Go to right page if right page not null
                if (rightPage != null) {
                    pageRight()
                }
                remain = dx + limit - offset
                offset = 0
            }
        }

        return remain
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

        if (current!!.imageView.canFlingVertically()) {
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
    }

    fun scrollInternal(dx: Float, dy: Float, x: Float, y: Float) {
        if (current == null) {
            return
        }

        var needFill = false
        var canImageScroll = true
        var remainX = dx.toInt()
        var remainY = dy.toInt()

        if (galleryView.isFirstScroll()) {
            canScrollBetweenPages = abs(dx) > abs(dy) * 1.5
        }

        while (remainX != 0 || remainY != 0) {
            if (offset == 0 && canImageScroll) {
                val image = current!!.imageView
                image.scroll(remainX, remainY, scrollRemain)
                remainX = scrollRemain[0]
                remainY = scrollRemain[1]
                canImageScroll = false
                deltaX = 0
                deltaY = 0
            } else if (remainX == 0 ||
                (leftPage == null && offset == 0 && remainX < 0) ||
                (rightPage == null && offset == 0 && remainX > 0)
            ) {
                // On edge
                overScrollEdge(remainX, remainY, x, y)
                remainX = 0
                remainY = 0
            } else if (canScrollBetweenPages) {
                remainX = scrollBetweenPages(remainX)
                canImageScroll = true
                needFill = true
                deltaX = 0
                deltaY = 0
            } else {
                remainX = 0
                remainY = 0
                deltaX = 0
                deltaY = 0
            }
        }

        if (needFill) {
            galleryView.requestFill()
        }
    }

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        scrollInternal(dx, dy, x, y)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val current = this.current
        if (current == null || offset != 0 || !current.imageView.isLoaded ||
            !current.imageView.canFling()
        ) {
            return
        }

        val image = current.imageView
        pageFling.startFling(
            velocityX.toInt(), image.getMinDx(), image.getMaxDx(),
            velocityY.toInt(), image.getMinDy(), image.getMaxDy(),
        )
    }

    override fun canScale(): Boolean {
        val current = this.current
        return current != null && offset == 0 && current.imageView.isLoaded
    }

    override fun onScale(focusX: Float, focusY: Float, scale: Float) {
        val current = this.current
        if (current == null || !current.imageView.isLoaded) {
            return
        }

        current.imageView.scale(focusX, focusY, scale)
        scaleValue = current.imageView.scale
    }

    override fun onUpdateAnimation(time: Long): Boolean {
        var invalidate = smoothScroller.calculate(time)
        invalidate = invalidate or pageFling.calculate(time)
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
        if (size <= 0 || current == null) {
            return
        }

        if (mode == MODE_LEFT_TO_RIGHT) {
            if (index == 0) {
                overScroller.overScroll(GLEdgeView.LEFT)
            } else {
                setCurrentIndex(index - 1)
            }
        } else {
            if (index >= size - 1) {
                overScroller.overScroll(GLEdgeView.LEFT)
                galleryView.onTransferEnd()
            } else {
                setCurrentIndex(index + 1)
            }
        }
    }

    override fun onPageRight() {
        val size = adapter!!.size()
        if (size <= 0 || current == null) {
            return
        }

        if (mode == MODE_LEFT_TO_RIGHT) {
            if (index >= size - 1) {
                overScroller.overScroll(GLEdgeView.RIGHT)
                galleryView.onTransferEnd()
            } else {
                setCurrentIndex(index + 1)
            }
        } else {
            if (index == 0) {
                overScroller.overScroll(GLEdgeView.RIGHT)
            } else {
                setCurrentIndex(index - 1)
            }
        }
    }

    override val isTapOrPressEnable: Boolean
        get() = !stopAnimationFinger

    override fun findPageByIndex(index: Int): GalleryPageView? {
        val current = this.current
        if (current != null && current.index == index) {
            return current
        }
        val previous = this.previous
        if (previous != null && previous.index == index) {
            return previous
        }
        val next = this.next
        if (next != null && next.index == index) {
            return next
        }
        return null
    }

    override fun getCurrentIndex(): Int {
        val current = this.current
        if (current != null) {
            return current.index
        } else {
            return GalleryPageView.INVALID_INDEX
        }
    }

    override val isReachEnd: Boolean
        get() {
            val adapter = this.adapter
            if (current == null || adapter == null) {
                return false
            }
            val size = adapter.size()
            // size <= 0 means empty or not-yet-known: no meaningful end.
            return size > 0 && index >= size - 1
        }

    override fun collectPageTransforms(out: MutableList<PageTransform>) {
        collectPageTransform(out, previous)
        collectPageTransform(out, current)
        collectPageTransform(out, next)
    }

    override fun setCurrentIndex(index: Int) {
        var size = adapter!!.size()
        if (size <= 0) {
            // Can't get size now, assume size is MAX
            size = Int.MAX_VALUE
        }
        if (index == this.index || index < 0 || index >= size) {
            return
        }
        if (current == null) {
            this.index = index
        } else if (index == this.index - 1) {
            // Cancel all animations
            cancelAllAnimations()
            // Reset parameters
            resetParameters()
            // Go to previous
            pagePrevious()
            // Request fill
            galleryView.requestFill()
        } else if (index == this.index + 1) {
            // Cancel all animations
            cancelAllAnimations()
            // Reset parameters
            resetParameters()
            // Go to next
            pageNext()
            // Request fill
            galleryView.requestFill()
        } else {
            this.index = index
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
            galleryView.requestFill()
        }
    }

    override fun getIndexUnder(x: Float, y: Float): Int {
        val current = this.current
        if (current == null) {
            return GalleryPageView.INVALID_INDEX
        } else {
            val intX = x.toInt()
            val intY = y.toInt()
            val previous = this.previous
            val next = this.next
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

    override val internalCurrentIndex: Int
        get() {
            var currentIndex = getCurrentIndex()
            if (currentIndex == GalleryPageView.INVALID_INDEX) {
                currentIndex = index
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

        private var dx = 0
        private var dy = 0
        private var lastX = 0
        private var lastY = 0

        init {
            interpolator = SmoothScrollerInterpolator
        }

        fun startSmoothScroll(dx: Int, dy: Int, duration: Int) {
            this.dx = dx
            this.dy = dy
            lastX = 0
            lastY = 0
            this.duration = duration.toLong()
            start()
            galleryView.invalidate()
        }

        override fun onCalculate(progress: Float) {
            val x = (dx * progress).toInt()
            val y = (dy * progress).toInt()
            var offsetX = x - lastX
            while (offsetX != 0) {
                val oldOffsetX = offsetX
                offsetX = scrollBetweenPages(offsetX)
                // Avoid loop infinitely
                if (offsetX == oldOffsetX) {
                    break
                } else {
                    galleryView.requestFill()
                }
            }
            lastX = x
            lastY = y
        }
    }

    private inner class PageFling(context: Context) : Fling(context) {

        private var velocityX = 0
        private var velocityY = 0
        private var dx = 0
        private var dy = 0
        private var lastX = 0
        private var lastY = 0
        private val temp = IntArray(2)

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
            val current = this@PagerLayoutManager.current
            if (current != null && (offsetX != 0 || offsetY != 0)) {
                current.imageView.scroll(-offsetX, -offsetY, temp)
            }
            lastX = x
            lastY = y
        }

        override fun onFinish() {
            val current = this@PagerLayoutManager.current ?: return

            val edgeView = galleryView.edgeView
            val imageView = current.imageView
            if (imageView.canFlingHorizontally()) {
                if (velocityX > 0 && leftPage == null && imageView.getMaxDx() == 0 &&
                    edgeView.isFinished(GLEdgeView.LEFT)
                ) {
                    edgeView.onAbsorb(velocityX, GLEdgeView.LEFT)
                } else if (velocityX < 0 && rightPage == null && imageView.getMinDx() == 0 &&
                    edgeView.isFinished(GLEdgeView.RIGHT)
                ) {
                    edgeView.onAbsorb(-velocityX, GLEdgeView.RIGHT)
                }
            }
            if (imageView.canFlingVertically()) {
                if (velocityY > 0 && imageView.getMaxDy() == 0 &&
                    edgeView.isFinished(GLEdgeView.TOP)
                ) {
                    edgeView.onAbsorb(velocityY, GLEdgeView.TOP)
                } else if (velocityY < 0 && imageView.getMinDy() == 0 &&
                    edgeView.isFinished(GLEdgeView.BOTTOM)
                ) {
                    edgeView.onAbsorb(-velocityY, GLEdgeView.BOTTOM)
                }
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
            val current = this@PagerLayoutManager.current ?: return

            val scale = MathUtils.lerp(startScale, endScale, progress)
            current.imageView.scale(focusX, focusY, scale / lastScale)
            lastScale = scale
            scaleValue = scale
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
        private val TAG: String = PagerLayoutManager::class.java.simpleName

        const val MODE_LEFT_TO_RIGHT = 0
        const val MODE_RIGHT_TO_LEFT = 1

        private const val DOUBLE_TAP_SCALE_DURATION = 300L
    }
}
