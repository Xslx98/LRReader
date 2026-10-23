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

package com.lanraragi.reader.widget.list

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A draggable scroll handle beside a [RecyclerView]: it fades in while
 * the list scrolls and out [SCROLL_BAR_DELAY] ms after it stops (ignoring
 * touches once hidden); dragging the handle scrolls the list in
 * proportion.
 *
 * Ported to Kotlin from seven332's EasyRecyclerView library (0.1.1),
 * behaviour unchanged.
 */
open class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnDragHandlerListener {
        fun onStartDragHandler()
        fun onEndDragHandler()
    }

    private val minHandlerHeight = (MIN_HANDLER_HEIGHT_DP * context.resources.displayMetrics.density + 0.5f).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var isDraggable = true
        set(draggable) {
            field = draggable
            if (dragged) dragged = false
            mainHandler.removeCallbacks(hideRunnable)
            hideRunnable.run()
        }

    private var recyclerView: RecyclerView? = null
    private var onScrollChangeListener: RecyclerView.OnScrollListener? = null
    private var adapter: RecyclerView.Adapter<*>? = null
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null

    private var handlerDrawable: Drawable? = null
    private var handlerOffset = INVALID
    private var handlerHeight = INVALID

    private var downX = INVALID.toFloat()
    private var downY = INVALID.toFloat()
    private var lastMotionY = INVALID.toFloat()
    private var dragged = false
    private var cantDrag = false

    private var listener: OnDragHandlerListener? = null

    private val showAnimator = ObjectAnimator.ofFloat(this, ALPHA, 1.0f).apply {
        interpolator = LinearOutSlowInInterpolator()
        duration = SCROLL_BAR_FADE_DURATION
    }
    private val hideAnimator = ObjectAnimator.ofFloat(this, ALPHA, 0.0f).apply {
        interpolator = FastOutLinearInInterpolator()
        duration = SCROLL_BAR_FADE_DURATION
        addListener(object : AnimatorListenerAdapter() {
            private var cancel = false

            override fun onAnimationCancel(animation: Animator) {
                cancel = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancel) cancel = false else visibility = INVISIBLE
            }
        })
    }

    private val hideRunnable = Runnable { hideAnimator.start() }

    init {
        alpha = 0.0f
        visibility = INVISIBLE
    }

    fun setOnDragHandlerListener(listener: OnDragHandlerListener?) {
        this.listener = listener
    }

    private fun updatePosition(show: Boolean) {
        val recyclerView = recyclerView ?: return
        val paddingTop = paddingTop
        val height = height - paddingTop - paddingBottom
        val offset = recyclerView.computeVerticalScrollOffset()
        val extent = recyclerView.computeVerticalScrollExtent()
        val range = recyclerView.computeVerticalScrollRange()
        if (height <= 0 || extent >= range || extent <= 0) return

        val endHeight = max(height * extent / range, minHandlerHeight)
        val endOffset = min(height * offset / range, height - endHeight)
        handlerOffset = endOffset + paddingTop
        handlerHeight = endHeight

        if (show) {
            if (hideAnimator.isRunning) {
                hideAnimator.cancel()
                showAnimator.start()
            } else if (visibility != VISIBLE && !showAnimator.isRunning) {
                visibility = VISIBLE
                showAnimator.start()
            }
            mainHandler.removeCallbacks(hideRunnable)
            if (!dragged) mainHandler.postDelayed(hideRunnable, SCROLL_BAR_DELAY)
        }
    }

    fun setHandlerDrawable(drawable: Drawable?) {
        handlerDrawable = drawable
        invalidate()
    }

    val isAttached: Boolean
        get() = recyclerView != null

    fun attachToRecyclerView(recyclerView: RecyclerView?) {
        if (recyclerView == null) return
        check(this.recyclerView == null) {
            "The FastScroller is already attached to a RecyclerView, call detachedFromRecyclerView first"
        }
        this.recyclerView = recyclerView
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePosition(true)
                invalidate()
            }
        }
        onScrollChangeListener = scrollListener
        recyclerView.addOnScrollListener(scrollListener)

        val adapter = recyclerView.adapter ?: return
        this.adapter = adapter
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update() {
                updatePosition(false)
                invalidate()
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = update()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = update()
        }
        adapterDataObserver = observer
        adapter.registerAdapterDataObserver(observer)
    }

    fun detachedFromRecyclerView() {
        onScrollChangeListener?.let { recyclerView?.removeOnScrollListener(it) }
        recyclerView = null
        onScrollChangeListener = null
        adapterDataObserver?.let { adapter?.unregisterAdapterDataObserver(it) }
        adapter = null
        adapterDataObserver = null
        alpha = 0.0f
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val handler = handlerDrawable
        if (recyclerView == null || handler == null) return
        if (handlerHeight == INVALID) updatePosition(false)
        if (handlerHeight == INVALID) return
        val paddingLeft = paddingLeft
        val saved = canvas.save()
        canvas.translate(paddingLeft.toFloat(), handlerOffset.toFloat())
        handler.setBounds(0, 0, width - paddingLeft - paddingRight, handlerHeight)
        handler.draw(canvas)
        canvas.restoreToCount(saved)
    }

    /** Shown, attached and measured: the handle can take a touch. */
    private fun isTouchable(): Boolean =
        isDraggable && visibility == VISIBLE && recyclerView != null && handlerHeight != INVALID

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isTouchable()) return false
        val action = event.action
        if (action == MotionEvent.ACTION_DOWN) cantDrag = false
        if (cantDrag) return false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                dragged = false
                downX = event.x
                downY = event.y
                // A down off the handle gives the whole gesture up.
                if (!onHandler(downY)) cantDrag = true
            }
            MotionEvent.ACTION_MOVE -> onDragMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragged) listener?.onEndDragHandler()
                dragged = false
                mainHandler.postDelayed(hideRunnable, SCROLL_BAR_DELAY)
            }
        }
        return !cantDrag
    }

    /** Past the touch slop, vertically on the handle: drag; sideways or off it: give the gesture up. */
    private fun onDragMove(event: MotionEvent) {
        if (!dragged) {
            val x = event.x
            val y = event.y
            // Touch slop.
            if (hypot(x - downX, y - downY) < touchSlop) return
            if (abs(x - downX) > abs(y - downY) || !onHandler(y)) {
                cantDrag = true
                return
            }
            startDrag()
        }
        scrollByDrag(event.y)
    }

    private fun onHandler(y: Float): Boolean = y >= handlerOffset && y <= handlerOffset + handlerHeight

    private fun startDrag() {
        dragged = true
        mainHandler.removeCallbacks(hideRunnable)
        // A down outside the handler drags from the handler's centre.
        lastMotionY = if (downY < handlerOffset || downY >= handlerOffset + handlerHeight) {
            (handlerOffset + handlerHeight / 2).toFloat()
        } else {
            downY
        }
        listener?.onStartDragHandler()
    }

    private fun scrollByDrag(y: Float) {
        val recyclerView = recyclerView ?: return
        val range = recyclerView.computeVerticalScrollRange()
        if (range <= 0) return
        val scroll = (range * (y - lastMotionY) / (height - paddingTop - paddingBottom)).toInt()
        recyclerView.scrollBy(0, scroll)
        lastMotionY = y
    }

    private companion object {
        const val INVALID = -1
        const val SCROLL_BAR_FADE_DURATION = 500L
        const val SCROLL_BAR_DELAY = 1000L
        const val MIN_HANDLER_HEIGHT_DP = 32

        val mainHandler = Handler(Looper.getMainLooper())
    }
}
