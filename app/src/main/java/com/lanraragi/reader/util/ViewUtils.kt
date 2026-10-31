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
package com.lanraragi.reader.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import kotlin.math.max
import kotlin.math.min

/** View helpers ported from yorozuya's ViewUtils and LayoutUtils (same results). */
object ViewUtils {

    /** [dp] in pixels, rounded to the nearest pixel. */
    @JvmStatic
    fun dp2pix(context: Context, dp: Float): Int = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Measures [v] exactly at [width] x [height]; WRAP_CONTENT measures unspecified. */
    @JvmStatic
    fun measureView(v: View, width: Int, height: Int) {
        v.measure(measureSpec(width), measureSpec(height))
    }

    private fun measureSpec(size: Int): Int =
        if (size == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(max(size, 0), View.MeasureSpec.EXACTLY)
        }

    /** [size] fitted to [measureSpec]; 0 under AT_MOST takes the whole spec size. */
    @JvmStatic
    fun getSuitableSize(size: Int, measureSpec: Int): Int {
        val specSize = View.MeasureSpec.getSize(measureSpec)
        return when (View.MeasureSpec.getMode(measureSpec)) {
            View.MeasureSpec.EXACTLY -> specSize
            View.MeasureSpec.AT_MOST -> if (size == 0) specSize else min(size, specSize)
            else -> size
        }
    }

    /** The visual bottom of [view]: its bottom plus its Y translation. */
    @JvmStatic
    fun getY2(view: View): Float = view.bottom + view.translationY

    /** True when (x, y) falls on [view] (translation included), widened by [slop]. */
    @JvmStatic
    fun isViewUnder(view: View?, x: Int, y: Int, slop: Int): Boolean {
        if (view == null) return false
        val tx = view.translationX
        val ty = view.translationY
        return x >= view.left + tx - slop && x < view.right + tx + slop &&
            y >= view.top + ty - slop && y < view.bottom + ty + slop
    }

    @JvmStatic
    fun removeFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    @JvmStatic
    fun translationYBy(view: View, offset: Float) {
        view.translationY += offset
    }

    /**
     * [view]'s position relative to [ancestor], accounting for the scroll of
     * every view in between, rounded into [location] (x, y).
     */
    @JvmStatic
    fun getLocationInAncestor(view: View, location: IntArray, ancestor: View) {
        require(location.size >= 2) { "location must be an array of two integers" }
        var x = view.left.toFloat()
        var y = view.top.toFloat()
        var parent = view.parent
        while (parent is View && parent !== ancestor) {
            x += parent.left - parent.scrollX
            y += parent.top - parent.scrollY
            parent = parent.parent
        }
        location[0] = (x + 0.5f).toInt()
        location[1] = (y + 0.5f).toInt()
    }

    /** The view [id] inside [activity]; a missing view is a layout bug and throws. */
    @JvmStatic
    fun requireView(activity: Activity, @IdRes id: Int): View =
        activity.findViewById(id) ?: throw NullPointerException("Can't find view with id: $id")

    /** The view [id] inside [dialog]; a missing view is a layout bug and throws. */
    @JvmStatic
    fun requireView(dialog: Dialog, @IdRes id: Int): View =
        dialog.findViewById(id) ?: throw NullPointerException("Can't find view with id: $id")

    /** The view [id] inside [view]; a missing view (or [view]) is a layout bug and throws. */
    @JvmStatic
    fun requireView(view: View?, @IdRes id: Int): View =
        (view ?: throw NullPointerException("Can't find view with id: $id in a null view"))
            .findViewById(id) ?: throw NullPointerException("Can't find view with id: $id")
}
