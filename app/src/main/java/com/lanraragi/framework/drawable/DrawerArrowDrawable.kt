/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.drawable

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.FloatProperty
import androidx.annotation.ColorInt
import com.lanraragi.reader.R
import com.lanraragi.reader.util.MathUtils

/**
 * A drawable that can draw a "Drawer hamburger" menu or an Arrow and animate between them.
 */
class DrawerArrowDrawable(context: Context, color: Int) : Drawable() {

    private val paint = Paint()

    private val barThickness: Float
    // The length of top and bottom bars when they merge into an arrow
    private val topBottomArrowSize: Float
    // The length of middle bar
    private val barSize: Float
    // The length of the middle bar when arrow is shaped
    private val middleArrowSize: Float
    // The space between bars when they are parallel
    private val barGap: Float
    // Whether bars should spin or not during progress
    private val spin: Boolean
    // Use Path instead of canvas operations so that if color has transparency, overlapping sections
    // wont look different
    private val path = Path()
    // The reported intrinsic size of the drawable.
    private val size: Int
    // Whether we should mirror animation when animation is reversed.
    private var verticalMirror = false
    // the amount that overlaps w/ bar size when rotation is max
    private val maxCutForBarSize: Float

    init {
        val resources = context.resources

        paint.isAntiAlias = true
        paint.color = color
        size = resources.getDimensionPixelSize(R.dimen.dad_drawable_size)
        // round this because having this floating may cause bad measurements
        barSize = Math.round(resources.getDimension(R.dimen.dad_bar_size)).toFloat()
        // round this because having this floating may cause bad measurements
        topBottomArrowSize = Math.round(resources.getDimension(R.dimen.dad_top_bottom_bar_arrow_size)).toFloat()
        barThickness = resources.getDimension(R.dimen.dad_thickness)
        // round this because having this floating may cause bad measurements
        barGap = Math.round(resources.getDimension(R.dimen.dad_gap_between_bars)).toFloat()
        spin = resources.getBoolean(R.bool.dad_spin_bars)
        middleArrowSize = resources.getDimension(R.dimen.dad_middle_bar_arrow_size)

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = barThickness

        maxCutForBarSize = (barThickness / 2 * Math.cos(ARROW_HEAD_ANGLE.toDouble())).toFloat()
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        // Interpolated widths of arrow bars
        val arrowSize = MathUtils.lerp(barSize, topBottomArrowSize, progress)
        val middleBarSize = MathUtils.lerp(barSize, middleArrowSize, progress)
        // Interpolated size of middle bar
        val middleBarCut = Math.round(MathUtils.lerp(0f, maxCutForBarSize, progress)).toFloat()
        // The rotation of the top and bottom bars (that make the arrow head)
        val rotation = MathUtils.lerp(0f, ARROW_HEAD_ANGLE, progress)

        // The whole canvas rotates as the transition happens
        val canvasRotate = MathUtils.lerp(-180, 0, progress).toFloat()
        val arrowWidth = Math.round(arrowSize * Math.cos(rotation.toDouble())).toFloat()
        val arrowHeight = Math.round(arrowSize * Math.sin(rotation.toDouble())).toFloat()

        path.rewind()
        val topBottomBarOffset = MathUtils.lerp(barGap + barThickness, -maxCutForBarSize, progress)

        val arrowEdge = -middleBarSize / 2
        // draw middle bar
        path.moveTo(arrowEdge + middleBarCut, 0f)
        path.rLineTo(middleBarSize - middleBarCut * 2, 0f)

        // bottom bar
        path.moveTo(arrowEdge, topBottomBarOffset)
        path.rLineTo(arrowWidth, arrowHeight)

        // top bar
        path.moveTo(arrowEdge, -topBottomBarOffset)
        path.rLineTo(arrowWidth, -arrowHeight)

        path.close()

        canvas.save()
        // Rotate the whole canvas if spinning, if not, rotate it 180 to get
        // the arrow pointing the other way for RTL.
        canvas.translate(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        if (spin) {
            canvas.rotate(canvasRotate * if (verticalMirror) -1 else 1)
        }
        canvas.drawPath(path, paint)

        canvas.restore()
    }

    fun setColor(@ColorInt color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun setAlpha(i: Int) {
        paint.alpha = i
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getIntrinsicHeight(): Int = size

    override fun getIntrinsicWidth(): Int = size

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // The interpolated version of the original progress
    var progress = 0f
        set(value) {
            // Mirror the spin when the morph reverses from the arrow.
            if (value == 1f) {
                verticalMirror = true
            } else if (value == 0f) {
                verticalMirror = false
            }
            field = value
            invalidateSelf()
        }

    fun setMenu(duration: Long) {
        setShape(false, duration)
    }

    fun setArrow(duration: Long) {
        setShape(true, duration)
    }

    private fun setShape(arrow: Boolean, duration: Long) {
        val endProgress = if (arrow) 1f else 0f
        if (progress == endProgress) return
        if (duration <= 0) {
            progress = endProgress
        } else {
            ObjectAnimator.ofFloat(this, PROGRESS, endProgress).apply {
                this.duration = duration
                setAutoCancel(true)
                start()
            }
        }
    }

    companion object {
        /**
         * Typed animator property: a `"progress"` string made ObjectAnimator look
         * up setProgress by reflection, and R8 renames it in release builds, so the icon
         * never morphed there (audit 2026-10-06e STAB-02).
         */
        @JvmField
        val PROGRESS: FloatProperty<DrawerArrowDrawable> = object : FloatProperty<DrawerArrowDrawable>("progress") {
            override fun setValue(drawable: DrawerArrowDrawable, value: Float) {
                drawable.progress = value
            }

            override fun get(drawable: DrawerArrowDrawable): Float = drawable.progress
        }

        // The angle in degrees that the arrow head is inclined at.
        private val ARROW_HEAD_ANGLE = Math.toRadians(45.0).toFloat()
    }
}
