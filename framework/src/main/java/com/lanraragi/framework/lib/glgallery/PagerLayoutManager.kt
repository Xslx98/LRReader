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
import com.lanraragi.framework.util.AnimationUtils
import com.lanraragi.framework.util.AssertUtils
import com.lanraragi.framework.util.MathUtils
import kotlin.math.abs
import kotlin.math.max

@Suppress("LargeClass") // the pages, drag offset and gesture flags are one state shared by fill, scroll and animations
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

    // A turn rotates the slots (pagePrevious/pageNext); none is reallocated.
    private var previous = PageSlot()
    private var current = PageSlot()
    private var next = PageSlot()

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

    // Two-page spreads (plan item 20). While they are inactive, every spread
    // branch below is skipped and this is the single-page pager. While they
    // are active, index is the first page (reading order) of the current slot.
    private var spreadMode = GalleryView.SPREAD_OFF
    private var spreadActive = false
    private val planner = SpreadPlanner()
    private val plannerPages = object : SpreadPlanner.Pages {
        override fun pageCount(): Int {
            val adapter = this@PagerLayoutManager.adapter ?: return 0
            return adapter.size()
        }

        override fun pageAspect(index: Int): Float {
            val adapter = this@PagerLayoutManager.adapter ?: return SpreadPlanner.UNKNOWN_ASPECT
            return adapter.pageAspect(index)
        }

        override fun isPairBreak(index: Int): Boolean {
            val adapter = this@PagerLayoutManager.adapter ?: return false
            return adapter.isPairBreak(index)
        }
    }

    /** What gestures move: the spread's shared viewport, or the single page's image. */
    private val currentTarget: PanTarget?
        get() {
            val first = current.first ?: return null
            return if (current.partner != null) current.viewport else first.imageView
        }

    /** The last page on screen in reading order; [index] for a single page. */
    private val lastShownIndex: Int
        get() {
            val partner = current.partner ?: return index
            return partner.index
        }

    private val firstPageSide: Int
        get() = if (mode == MODE_LEFT_TO_RIGHT) SpreadViewport.LEFT else SpreadViewport.RIGHT

    // The stored parity for the next landing only (open or resume); later
    // landings pair from what the planner knows.
    private var landingParity = SpreadPlanner.PARITY_UNKNOWN

    // currentSpreadParity for parityIndex, so a frame costs no planner walk.
    private var parityIndex = GalleryPageView.INVALID_INDEX
    private var parity = SpreadPlanner.PARITY_UNKNOWN

    fun setSpreadMode(spreadMode: Int) {
        if (this.spreadMode == spreadMode) {
            return
        }
        this.spreadMode = spreadMode
        if (adapter != null) {
            galleryView.requestFill()
        }
    }

    /** See [GalleryView.setSpreadPairing]; a change re-pairs the current spread. */
    fun setSpreadPairing(firstPageAlone: Boolean, shifted: Boolean, parity: Int) {
        if (planner.firstPageAlone == firstPageAlone && planner.shifted == shifted &&
            landingParity == parity
        ) {
            return
        }
        planner.firstPageAlone = firstPageAlone
        planner.shifted = shifted
        landingParity = parity
        parityIndex = GalleryPageView.INVALID_INDEX
        if (spreadActive && adapter != null) {
            cancelAllAnimations()
            removeAllPages()
            resetParameters()
            galleryView.requestFill()
        }
    }

    override val currentSpreadParity: Int
        get() {
            if (!spreadActive || current.first == null) {
                return SpreadPlanner.PARITY_UNKNOWN
            }
            if (parityIndex != index) {
                parityIndex = index
                parity = planner.parityOf(plannerPages, index)
            }
            return parity
        }

    // Spreads follow the view's shape; switching rebuilds the slots around
    // index, the first page the reader sees.
    private fun updateSpreadActive(width: Int, height: Int) {
        val active = when (spreadMode) {
            GalleryView.SPREAD_ALWAYS -> true
            GalleryView.SPREAD_LANDSCAPE -> width > height
            else -> false
        }
        if (active == spreadActive) {
            return
        }
        spreadActive = active
        cancelAllAnimations()
        removeAllPages()
        resetParameters()
    }

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
        resetScaleOffsets()
    }

    private fun resetScaleOffsets() {
        current.first?.imageView?.setScaleOffset(scaleMode, startPosition, scaleValue)
        previous.first?.imageView?.setScaleOffset(scaleMode, startPosition, scaleValue)
        next.first?.imageView?.setScaleOffset(scaleMode, startPosition, scaleValue)
    }

    fun setStartPosition(startPosition: Int) {
        if (this.startPosition == startPosition) {
            return
        }
        this.startPosition = startPosition
        resetScaleOffsets()
    }

    override fun onAttach(adapter: GalleryView.Adapter) {
        AssertUtils.assertNull("The PagerLayoutManager is attached", this.adapter)
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
        unpair(page)
        galleryView.removeComponent(page)
        (adapter ?: throw AssertionError(NOT_ATTACHED)).unbind(page)
        galleryView.releasePage(page)
    }

    private fun clearSlot(slot: PageSlot) {
        val first = slot.first
        if (first != null) {
            removePage(first)
            slot.first = null
        }
        val partner = slot.partner
        if (partner != null) {
            removePage(partner)
            slot.partner = null
        }
    }

    private fun removeAllPages() {
        clearSlot(previous)
        clearSlot(current)
        clearSlot(next)
    }

    override fun onDetach(): GalleryView.Adapter {
        val adapter = this.adapter ?: throw AssertionError(NOT_ATTACHED)

        // Cancel all animations
        cancelAllAnimations()

        // Remove all view
        removeProgress()
        removeErrorView()
        removeAllPages()

        // Clear iterator
        this.adapter = null

        return adapter
    }

    private val leftSlot: PageSlot
        get() = when (mode) {
            MODE_LEFT_TO_RIGHT -> previous
            else -> next
        }

    private val rightSlot: PageSlot
        get() = when (mode) {
            MODE_LEFT_TO_RIGHT -> next
            else -> previous
        }

    private val leftPage: GalleryPageView?
        get() = leftSlot.first

    private val rightPage: GalleryPageView?
        get() = rightSlot.first

    private fun obtainPage(): GalleryPageView {
        val page = galleryView.obtainPage()
        page.imageView.setScaleOffset(scaleMode, startPosition, scaleValue)
        return page
    }

    // A page view from the pool keeps the default layout params of its last
    // add; handing them back saves a LayoutParams allocation on every turn.
    private fun addPage(page: GalleryPageView) {
        galleryView.addComponent(page, page.layoutParams)
    }

    /** Shows page [index] in the empty [slot] and returns its page view. */
    private fun fillSlot(slot: PageSlot, index: Int): GalleryPageView {
        val page = obtainPage()
        slot.first = page
        addPage(page)
        (adapter ?: throw AssertionError(NOT_ATTACHED)).bind(page, index)
        return page
    }

    /** Shows [spread] in the empty [slot]: its first page, and the second as the partner. */
    private fun fillSpreadSlot(slot: PageSlot, spread: Spread) {
        fillSlot(slot, spread.first)
        if (spread.isPair) {
            addPartner(slot, spread.last)
        }
    }

    private fun addPartner(slot: PageSlot, index: Int) {
        val first = slot.first ?: return
        val partner = obtainPage()
        slot.partner = partner
        addPage(partner)
        (adapter ?: throw AssertionError(NOT_ATTACHED)).bind(partner, index)
        // Right to left, the first page in reading order is on the right.
        val viewport = slot.viewport
        viewport.reset()
        val firstSide = firstPageSide
        val partnerSide = 1 - firstSide
        first.imageView.attachSpread(viewport, firstSide)
        first.setInfoSide(firstSide)
        partner.imageView.attachSpread(viewport, partnerSide)
        partner.setInfoSide(partnerSide)
    }

    private fun unpair(page: GalleryPageView) {
        page.imageView.detachSpread()
        page.setInfoSide(GalleryPageView.INFO_CENTRED)
    }

    /** Re-pairs [slot] as [spread], which starts with the slot's first page. */
    private fun repair(slot: PageSlot, spread: Spread) {
        val first = slot.first ?: return
        val partner = slot.partner
        if (partner != null) {
            removePage(partner)
            slot.partner = null
            unpair(first)
        }
        if (spread.isPair) {
            addPartner(slot, spread.last)
        }
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
        val adapter = this.adapter ?: throw AssertionError(NOT_ATTACHED)
        val size = adapter.size()
        val error = adapter.getError()

        if (size == GalleryProvider.STATE_WAIT) { // Wait here, show progress bar
            // Remove error view and all pages
            removeErrorView()
            removeAllPages()
            showProgress()
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
            showError(errorStr)
        } else {
            // Remove progress and error view
            removeProgress()
            removeErrorView()
            fillPages(size)
        }
    }

    private fun showProgress() {
        // Ensure progress
        var progress = this.progress
        if (progress == null) {
            progress = galleryView.obtainProgress()
            this.progress = progress
            galleryView.addComponent(progress)
        }

        // Place progress center
        placeCenter(progress)
    }

    private fun showError(errorStr: String) {
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
    }

    // The page path of onFill: the current page and its neighbours, laid
    // out around the drag offset.
    private fun fillPages(size: Int) {
        val galleryView = this.galleryView
        val width = galleryView.width
        val height = galleryView.height

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

        updateSpreadActive(width, height)
        if (spreadActive) {
            fillSpreadPages(size)
            layoutSlots(width, height)
            return
        }

        // Ensure pages
        val current = this.current
        if (current.first == null) {
            fillSlot(current, index)
        }
        val previous = this.previous
        if (previous.first == null && index > 0) {
            fillSlot(previous, index - 1)
        } else if (previous.first != null && index == 0) {
            clearSlot(previous)
        }
        val next = this.next
        if (next.first == null && index < size - 1) {
            fillSlot(next, index + 1)
        } else if (next.first != null && index == size - 1) {
            clearSlot(next)
        }

        layoutSlots(width, height)
    }

    // The spread path of fillPages: the current spread and its neighbours.
    private fun fillSpreadPages(size: Int) {
        val current = this.current
        if (current.first == null) {
            // Open, resume, a jump, rotation, a data change or switching
            // spreads on: index is the page asked for, landing finds its spread.
            val landing = planner.spreadForLanding(plannerPages, index, landingParity)
            landingParity = SpreadPlanner.PARITY_UNKNOWN
            index = landing.first
            fillSpreadSlot(current, landing)
        }
        val previous = this.previous
        if (previous.first == null && index > 0) {
            fillSpreadSlot(previous, planner.spreadBefore(plannerPages, index))
        } else if (previous.first != null && index == 0) {
            clearSlot(previous)
        }
        val last = current.lastIndex()
        val next = this.next
        if (next.first == null && last < size - 1) {
            fillSpreadSlot(next, planner.spreadAfter(plannerPages, last))
        } else if (next.first != null && last >= size - 1) {
            clearSlot(next)
        }
    }

    // Lays the slots out around the drag offset; a spread's two page views
    // both span their slot.
    private fun layoutSlots(width: Int, height: Int) {
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
        layoutSlot(current, widthSpec, heightSpec, offset, width, height)
        if (leftPage != null) {
            layoutSlot(leftSlot, widthSpec, heightSpec, -interval - width + offset, width, height)
        }
        if (rightPage != null) {
            layoutSlot(rightSlot, widthSpec, heightSpec, width + interval + offset, width, height)
        }
    }

    @Suppress("LongParameterList") // a slot's measure specs and its rect
    private fun layoutSlot(slot: PageSlot, widthSpec: Int, heightSpec: Int, left: Int, width: Int, height: Int) {
        val first = slot.first ?: return
        layoutPage(first, widthSpec, heightSpec, left, 0, left + width, height)
        val partner = slot.partner ?: return
        layoutPage(partner, widthSpec, heightSpec, left, 0, left + width, height)
        // Before any image arrives too: tap lookup reads the halves from it.
        slot.viewport.layout(width, height)
    }

    override fun onDown() {
        deltaX = 0
        deltaY = 0
        stopAnimationFinger = cancelAllAnimations()
    }

    override fun onUp() {
        galleryView.edgeView.onRelease()

        if (current.first == null) {
            return
        }

        // A drag or fling that ends pulling the forward edge on the last page
        // (empty next slot) is a page-turn attempt past the end, just like the
        // boundary branches in onPageLeft()/onPageRight(). deltaX is only
        // non-zero when the gesture ended in overScrollEdge(), and its sign
        // tells which edge was pulled. Note deltaX reflects only the FINAL
        // motion event's residual — the image-scroll branch in scrollInternal()
        // re-zeroes it on every event while offset == 0 — which is what keeps
        // a pull-then-retreat gesture from firing here. canScrollBetweenPages
        // (horizontal-dominant gesture, set on first scroll) filters out
        // vertical-dominant drags whose last event drifts a few pixels
        // horizontally into the edge branch.
        val pullingForward = canScrollBetweenPages && when (mode) {
            MODE_LEFT_TO_RIGHT -> deltaX > 0
            MODE_RIGHT_TO_LEFT -> deltaX < 0
            else -> false
        }
        if (offset == 0 && next.first == null && pullingForward) {
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
        val image = currentTarget
        if (image == null || !image.isLoaded) {
            return
        }

        val scales = scaleDefault
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
        if (spreadActive) {
            spreadPagePrevious()
            return
        }
        index--

        // The next slot falls off the end and wraps around to previous.
        val recycled = next
        clearSlot(recycled)
        next = current
        current = previous
        previous = recycled

        if (index > 0) {
            fillSlot(recycled, index - 1)
        }
    }

    // pagePrevious over spreads: the previous slot becomes current.
    private fun spreadPagePrevious() {
        val recycled = next
        clearSlot(recycled)
        next = current
        current = previous
        previous = recycled

        val first = current.first
        index = if (first != null) first.index else index - 1
        if (first != null && index > 0) {
            fillSpreadSlot(recycled, planner.spreadBefore(plannerPages, index))
        }
    }

    // pageNext over spreads: the next slot becomes current.
    private fun spreadPageNext(size: Int) {
        val last = lastShownIndex
        if (last >= size - 1) {
            return
        }
        val recycled = previous
        clearSlot(recycled)
        previous = current
        current = next
        next = recycled

        val first = current.first
        index = if (first != null) first.index else last + 1
        val newLast = current.lastIndex()
        if (first != null && newLast < size - 1) {
            fillSpreadSlot(recycled, planner.spreadAfter(plannerPages, newLast))
        }
    }

    private fun pageNext() {
        val adapter = this.adapter ?: throw AssertionError(NOT_ATTACHED)
        val size = adapter.size()
        if (spreadActive) {
            spreadPageNext(size)
            return
        }
        if (index >= size - 1) {
            return
        }
        index++

        // The previous slot falls off the end and wraps around to next.
        val recycled = previous
        clearSlot(recycled)
        previous = current
        current = next
        next = recycled

        if (index < size - 1) {
            fillSlot(recycled, index + 1)
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

    private fun overScrollEdge(dx: Int, dy: Int, x: Float, y: Float) {
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

        val target = currentTarget
        if (target != null && target.canFlingVertically()) {
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

    private fun scrollInternal(dx: Float, dy: Float, x: Float, y: Float) {
        if (current.first == null) {
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
                // A page turn earlier in this loop replaced the current page.
                val image = currentTarget ?: break
                image.scroll(remainX, remainY, scrollRemain)
                invalidateSpread()
                remainX = scrollRemain[0]
                remainY = scrollRemain[1]
                canImageScroll = false
                deltaX = 0
                deltaY = 0
            } else if (isOnEdge(remainX)) {
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

    private fun isOnEdge(remainX: Int): Boolean =
        remainX == 0 ||
            (leftPage == null && offset == 0 && remainX < 0) ||
            (rightPage == null && offset == 0 && remainX > 0)

    override fun onScroll(dx: Float, dy: Float, totalX: Float, totalY: Float, x: Float, y: Float) {
        scrollInternal(dx, dy, x, y)
    }

    // An ImageView invalidates itself when it moves; a viewport has no view.
    private fun invalidateSpread() {
        if (current.partner != null) {
            galleryView.invalidate()
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val image = currentTarget
        if (image == null || offset != 0) {
            return
        }
        if (!image.isLoaded || !image.canFling()) {
            return
        }

        pageFling.startFling(
            velocityX.toInt(), image.getMinDx(), image.getMaxDx(),
            velocityY.toInt(), image.getMinDy(), image.getMaxDy(),
        )
    }

    override fun canScale(): Boolean {
        val target = currentTarget
        return target != null && offset == 0 && target.isLoaded
    }

    override fun onScale(focusX: Float, focusY: Float, scale: Float) {
        val target = currentTarget
        if (target == null || !target.isLoaded) {
            return
        }

        target.scale(focusX, focusY, scale)
        updateScaleValue(target.scale)
    }

    // A single page's zoom carries over to the next page in SCALE_FIXED; a
    // spread's joint zoom does not.
    private fun updateScaleValue(scale: Float) {
        if (current.partner == null) {
            scaleValue = scale
        } else {
            galleryView.invalidate()
        }
    }

    override fun onUpdateAnimation(time: Long): Boolean {
        var invalidate = smoothScroller.calculate(time)
        invalidate = invalidate or pageFling.calculate(time)
        invalidate = invalidate or smoothScaler.calculate(time)
        invalidate = invalidate or overScroller.calculate(time)
        return invalidate
    }

    override fun onDataChanged() {
        AssertUtils.assertNotNull(NOT_ATTACHED, adapter)

        // Cancel all animations
        cancelAllAnimations()
        // Remove all views
        removeProgress()
        removeErrorView()
        removeAllPages()
        // Reset parameters
        resetParameters()
        // Pair breaks may have moved.
        parityIndex = GalleryPageView.INVALID_INDEX
        galleryView.requestFill()
    }

    /**
     * A decode told the planner page [page]'s aspect. Re-plan from the
     * current spread onward; of what lies behind, only the previous slot
     * may change, and only while it is off screen. The pages the reader
     * already passed never move.
     */
    override fun onPageAspectKnown(page: Int) {
        if (!spreadActive || current.first == null) {
            return
        }
        if (page < index) {
            val previous = this.previous
            if (offset == 0 && page in previous && !previous.holds(planner.spreadBefore(plannerPages, index))) {
                clearSlot(previous)
                galleryView.requestFill()
            }
            return
        }
        val planned = planner.spreadFrom(plannerPages, index)
        if (!current.holds(planned)) {
            // Only a wide page can turn up, so the partner moves on to the
            // next spread. The gestures' target changes with it.
            pageFling.cancel()
            smoothScaler.cancel()
            repair(current, planned)
            clearSlot(next)
            galleryView.requestFill()
        } else if (next.first != null && !next.holds(planner.spreadAfter(plannerPages, planned.last))) {
            clearSlot(next)
            galleryView.requestFill()
        }
    }

    override fun onPageLeft() {
        val size = (adapter ?: throw AssertionError(NOT_ATTACHED)).size()
        if (size <= 0 || current.first == null) {
            return
        }

        if (mode == MODE_LEFT_TO_RIGHT) {
            if (index == 0) {
                overScroller.overScroll(GLEdgeView.LEFT)
            } else {
                setCurrentIndex(index - 1)
            }
        } else {
            if (lastShownIndex >= size - 1) {
                overScroller.overScroll(GLEdgeView.LEFT)
                galleryView.onTransferEnd()
            } else {
                setCurrentIndex(lastShownIndex + 1)
            }
        }
    }

    override fun onPageRight() {
        val size = (adapter ?: throw AssertionError(NOT_ATTACHED)).size()
        if (size <= 0 || current.first == null) {
            return
        }

        if (mode == MODE_LEFT_TO_RIGHT) {
            if (lastShownIndex >= size - 1) {
                overScroller.overScroll(GLEdgeView.RIGHT)
                galleryView.onTransferEnd()
            } else {
                setCurrentIndex(lastShownIndex + 1)
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
        val current = this.current.first
        if (current != null && current.index == index) {
            return current
        }
        val previous = this.previous.first
        if (previous != null && previous.index == index) {
            return previous
        }
        val next = this.next.first
        if (next != null && next.index == index) {
            return next
        }
        return findPartnerByIndex(index)
    }

    private fun findPartnerByIndex(index: Int): GalleryPageView? {
        val current = this.current.partner
        if (current != null && current.index == index) {
            return current
        }
        val previous = this.previous.partner
        if (previous != null && previous.index == index) {
            return previous
        }
        val next = this.next.partner
        if (next != null && next.index == index) {
            return next
        }
        return null
    }

    override fun getCurrentIndex(): Int {
        val current = this.current.first
        if (current != null) {
            return current.index
        } else {
            return GalleryPageView.INVALID_INDEX
        }
    }

    override val isReachEnd: Boolean
        get() {
            val adapter = this.adapter
            if (current.first == null || adapter == null) {
                return false
            }
            val size = adapter.size()
            // size <= 0 means empty or not-yet-known: no meaningful end.
            return size > 0 && lastShownIndex >= size - 1
        }

    override val currentLastIndex: Int
        get() {
            val partner = current.partner ?: return getCurrentIndex()
            return partner.index
        }

    override fun collectPageTransforms(out: MutableList<PageTransform>) {
        collectPageTransform(out, previous.first)
        collectPageTransform(out, current.first)
        collectPageTransform(out, next.first)
        collectPageTransform(out, previous.partner)
        collectPageTransform(out, current.partner)
        collectPageTransform(out, next.partner)
    }

    override fun setCurrentIndex(index: Int) {
        var size = (adapter ?: throw AssertionError(NOT_ATTACHED)).size()
        if (size <= 0) {
            // Can't get size now, assume size is MAX
            size = Int.MAX_VALUE
        }
        if (index == this.index || index < 0 || index >= size) {
            return
        }
        if (spreadActive && current.first != null) {
            setCurrentSpread(index)
            return
        }
        if (current.first == null) {
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

    // setCurrentIndex over spreads: lands on the spread holding [index]. A
    // neighbour turns like a page turn; anything else lands afresh.
    private fun setCurrentSpread(index: Int) {
        if (index in current) {
            return
        }
        cancelAllAnimations()
        resetParameters()
        if (index in previous) {
            pagePrevious()
        } else if (index in next) {
            pageNext()
        } else {
            this.index = index
            removeProgress()
            removeErrorView()
            removeAllPages()
        }
        galleryView.requestFill()
    }

    override fun getIndexUnder(x: Float, y: Float): Int {
        if (current.first == null) {
            return GalleryPageView.INVALID_INDEX
        }
        val intX = x.toInt()
        val intY = y.toInt()
        var index = indexUnder(current, x, intX, intY)
        if (index == GalleryPageView.INVALID_INDEX) {
            index = indexUnder(previous, x, intX, intY)
        }
        if (index == GalleryPageView.INVALID_INDEX) {
            index = indexUnder(next, x, intX, intY)
        }
        return index
    }

    // The page of [slot] under the point; for a spread, the half it falls in.
    private fun indexUnder(slot: PageSlot, x: Float, intX: Int, intY: Int): Int {
        val first = slot.first ?: return GalleryPageView.INVALID_INDEX
        val bounds = first.bounds()
        if (!bounds.contains(intX, intY)) {
            return GalleryPageView.INVALID_INDEX
        }
        val partner = slot.partner ?: return first.index
        return if (slot.viewport.sideAt(x - bounds.left) == firstPageSide) first.index else partner.index
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
            val target = currentTarget
            if (target != null && (offsetX != 0 || offsetY != 0)) {
                target.scroll(-offsetX, -offsetY, temp)
                invalidateSpread()
            }
            lastX = x
            lastY = y
        }

        override fun onFinish() {
            val imageView = currentTarget ?: return

            val edgeView = galleryView.edgeView
            if (imageView.canFlingHorizontally()) {
                if (velocityX > 0) {
                    if (leftPage == null && imageView.getMaxDx() == 0 && edgeView.isFinished(GLEdgeView.LEFT)) {
                        edgeView.onAbsorb(velocityX, GLEdgeView.LEFT)
                    }
                } else if (velocityX < 0) {
                    if (rightPage == null && imageView.getMinDx() == 0 && edgeView.isFinished(GLEdgeView.RIGHT)) {
                        edgeView.onAbsorb(-velocityX, GLEdgeView.RIGHT)
                    }
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
            val target = currentTarget ?: return

            val scale = MathUtils.lerp(startScale, endScale, progress)
            target.scale(focusX, focusY, scale / lastScale)
            lastScale = scale
            updateScaleValue(scale)
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

        private const val NOT_ATTACHED = "The PagerLayoutManager is not attached"
    }
}
