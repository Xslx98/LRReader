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
import com.lanraragi.reader.R
import com.lanraragi.reader.util.MathUtils

class AddDeleteDrawable(context: Context, color: Int) : Drawable() {

    private val paint = Paint()
    private val path = Path()

    private val size: Int


    init {
        val resources = context.resources

        size = resources.getDimensionPixelSize(R.dimen.add_size)
        val barThickness = Math.round(resources.getDimension(R.dimen.add_thickness)).toFloat()

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = barThickness

        val halfSize = (size / 2).toFloat()
        path.moveTo(0f, -halfSize)
        path.lineTo(0f, halfSize)
        path.moveTo(-halfSize, 0f)
        path.lineTo(halfSize, 0f)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val canvasRotate = MathUtils.lerp(0f, 135f, progress)

        canvas.save()
        canvas.translate(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        canvas.rotate(canvasRotate)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    override fun getIntrinsicHeight(): Int = size * 6 / 5

    override fun getIntrinsicWidth(): Int = size * 6 / 5

    var progress = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    fun setAdd(duration: Long) {
        setShape(false, duration)
    }

    fun setDelete(duration: Long) {
        setShape(true, duration)
    }

    private fun setShape(delete: Boolean, duration: Long) {
        val endProgress = if (delete) 1f else 0f
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

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Typed animator property: a `"progress"` string made ObjectAnimator look
         * up setProgress by reflection, and R8 renames it in release builds, so the icon
         * never morphed there (audit 2026-10-06e STAB-02).
         */
        @JvmField
        val PROGRESS: FloatProperty<AddDeleteDrawable> = object : FloatProperty<AddDeleteDrawable>("progress") {
            override fun setValue(drawable: AddDeleteDrawable, value: Float) {
                drawable.progress = value
            }

            override fun get(drawable: AddDeleteDrawable): Float = drawable.progress
        }
    }
}
