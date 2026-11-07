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

package com.lanraragi.framework.widget.recyclerview

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlin.math.abs
import kotlin.math.max

class AutoStaggeredGridLayoutManager(columnSize: Int, orientation: Int) : StaggeredGridLayoutManager(1, orientation) {

    var columnSize = -1
        set(value) {
            if (value == field) {
                return
            }
            field = value
            columnSizeChanged = true
        }

    private var columnSizeChanged = true

    var strategy = 0
        set(value) {
            if (value == field) {
                return
            }
            field = value
            columnSizeChanged = true
        }

    private val listeners: MutableList<OnUpdateSpanCountListener> = ArrayList()

    init {
        this.columnSize = columnSize
    }

    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        if (columnSizeChanged && columnSize > 0) {
            val totalSpace: Int
            if (orientation == VERTICAL) {
                check(View.MeasureSpec.EXACTLY == View.MeasureSpec.getMode(widthSpec)) {
                    "RecyclerView need a fixed width for AutoStaggeredGridLayoutManager"
                }
                totalSpace = View.MeasureSpec.getSize(widthSpec) - paddingRight - paddingLeft
            } else {
                check(View.MeasureSpec.EXACTLY == View.MeasureSpec.getMode(heightSpec)) {
                    "RecyclerView need a fixed height for AutoStaggeredGridLayoutManager"
                }
                totalSpace = View.MeasureSpec.getSize(heightSpec) - paddingTop - paddingBottom
            }

            val spanCount = when (strategy) {
                STRATEGY_SUITABLE_SIZE -> getSpanCountForSuitableSize(totalSpace, columnSize)
                else -> getSpanCountForMinSize(totalSpace, columnSize)
            }
            setSpanCount(spanCount)
            columnSizeChanged = false

            for (i in 0 until listeners.size) {
                listeners[i].onUpdateSpanCount(spanCount)
            }
        }
        super.onMeasure(recycler, state, widthSpec, heightSpec)
    }

    fun addOnUpdateSpanCountListener(listener: OnUpdateSpanCountListener) {
        listeners.add(listener)
    }

    fun removeOnUpdateSpanCountListener(listener: OnUpdateSpanCountListener) {
        listeners.remove(listener)
    }

    interface OnUpdateSpanCountListener {
        fun onUpdateSpanCount(spanCount: Int)
    }

    companion object {
        const val STRATEGY_MIN_SIZE = 0
        const val STRATEGY_SUITABLE_SIZE = 1

        @JvmStatic
        fun getSpanCountForSuitableSize(total: Int, single: Int): Int {
            val span = total / single
            if (span <= 0) {
                return 1
            }
            val span2 = span + 1
            val deviation = abs(1 - total / span / single.toFloat())
            val deviation2 = abs(1 - total / span2 / single.toFloat())
            return if (deviation < deviation2) span else span2
        }

        @JvmStatic
        fun getSpanCountForMinSize(total: Int, single: Int): Int = max(1, total / single)
    }
}
