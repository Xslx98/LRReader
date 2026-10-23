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

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Scrolling helpers over [LinearLayoutManager] and
 * [StaggeredGridLayoutManager]. Ported to Kotlin from seven332's
 * EasyRecyclerView library (0.1.1); the staggered smooth scroll asks the
 * layout manager's public computeScrollVectorForPosition instead of
 * reflecting into its private calculateScrollDirectionForPosition (same
 * vector).
 */
object LayoutManagerUtils {

    fun interface OnScrollToPositionListener {
        fun onScrollToPosition(position: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun scrollToPositionWithOffset(layoutManager: RecyclerView.LayoutManager?, position: Int, offset: Int) {
        when (layoutManager) {
            is LinearLayoutManager -> layoutManager.scrollToPositionWithOffset(position, offset)
            is StaggeredGridLayoutManager -> layoutManager.scrollToPositionWithOffset(position, offset)
            else -> error("Can't do scrollToPositionWithOffset for ${layoutManager?.javaClass?.name}")
        }
    }

    /** Smooth-scrolls to [position], at [millisecondsPerInch] when positive. */
    @JvmStatic
    @JvmOverloads
    fun smoothScrollToPosition(
        layoutManager: RecyclerView.LayoutManager?,
        context: Context,
        position: Int,
        millisecondsPerInch: Int = -1
    ) {
        val vectors = when (layoutManager) {
            is LinearLayoutManager -> layoutManager
            is StaggeredGridLayoutManager -> layoutManager
            else -> error("Can't do smoothScrollToPosition for ${layoutManager?.javaClass?.name}")
        } as RecyclerView.SmoothScroller.ScrollVectorProvider
        val smoothScroller = SimpleSmoothScroller(context, millisecondsPerInch.toFloat(), vectors)
        smoothScroller.targetPosition = position
        layoutManager.startSmoothScroll(smoothScroller)
    }

    /**
     * 200 ms later: smooth-scrolls to [position] when it is less than a
     * screen away, else jumps there and tells [listener].
     */
    @JvmStatic
    fun scrollToPositionProperly(
        layoutManager: RecyclerView.LayoutManager?,
        context: Context,
        position: Int,
        listener: OnScrollToPositionListener?
    ) {
        mainHandler.postDelayed({
            val first = getFirstVisibleItemPosition(layoutManager)
            val last = getLastVisibleItemPosition(layoutManager)
            val offset = abs(position - first)
            val max = last - first
            if (offset < max && max > 0) {
                // offset / max is an integer division (0 here), as in the library: 100 ms per inch.
                smoothScrollToPosition(layoutManager, context, position, lerp(100, 25, (offset / max).toFloat()))
            } else {
                scrollToPositionWithOffset(layoutManager, position, 0)
                listener?.onScrollToPosition(position)
            }
        }, PROPER_SCROLL_DELAY)
    }

    @JvmStatic
    fun getFirstVisibleItemPosition(layoutManager: RecyclerView.LayoutManager?): Int = when (layoutManager) {
        is LinearLayoutManager -> layoutManager.findFirstVisibleItemPosition()
        is StaggeredGridLayoutManager -> layoutManager.findFirstVisibleItemPositions(null).min()
        else -> error("Can't do getFirstVisibleItemPosition for ${layoutManager?.javaClass?.name}")
    }

    @JvmStatic
    fun getLastVisibleItemPosition(layoutManager: RecyclerView.LayoutManager?): Int = when (layoutManager) {
        is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
        is StaggeredGridLayoutManager -> layoutManager.findLastVisibleItemPositions(null).max()
        else -> error("Can't do getLastVisibleItemPosition for ${layoutManager?.javaClass?.name}")
    }

    private fun lerp(start: Int, stop: Int, amount: Float): Int = start + ((stop - start) * amount).toInt()

    private const val PROPER_SCROLL_DELAY = 200L

    /** A LinearSmoothScroller at a fixed speed, when [millisecondsPerInch] is positive. */
    private class SimpleSmoothScroller(
        context: Context,
        millisecondsPerInch: Float,
        private val vectors: RecyclerView.SmoothScroller.ScrollVectorProvider
    ) : LinearSmoothScroller(context) {

        private val millisecondsPerPx = millisecondsPerInch / context.resources.displayMetrics.densityDpi

        override fun computeScrollVectorForPosition(targetPosition: Int): PointF? =
            vectors.computeScrollVectorForPosition(targetPosition)

        override fun calculateTimeForScrolling(dx: Int): Int =
            if (millisecondsPerPx <= 0) super.calculateTimeForScrolling(dx) else ceil(abs(dx) * millisecondsPerPx).toInt()
    }
}
