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

package com.lanraragi.framework.lib.glview.widget

import android.opengl.Matrix
import androidx.annotation.IntDef
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.view.GLView

class GLEdgeView(color: Int) : GLView() {

    @IntDef(TOP, LEFT, BOTTOM, RIGHT)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Direction

    private val effects = Array(4) { GLEdgeEffect(color) }
    private val matrix = FloatArray(4 * 16)

    override fun onLayout(
        changeSize: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (!changeSize) return

        val w = right - left
        val h = bottom - top
        for (i in 0 until 4) {
            if ((i and 1) == 0) { // top or bottom
                effects[i].setSize(w, h)
            } else { // left or right
                effects[i].setSize(h, w)
            }
        }

        // Set up transforms for the four edges. Without transforms an
        // EdgeEffect draws the TOP edge from (0, 0) to (w, Y * h) where Y
        // is some factor < 1. For other edges we need to move, rotate, and
        // flip the effects into proper places.
        Matrix.setIdentityM(matrix, TOP_M)
        Matrix.setIdentityM(matrix, LEFT_M)
        Matrix.setIdentityM(matrix, BOTTOM_M)
        Matrix.setIdentityM(matrix, RIGHT_M)

        Matrix.rotateM(matrix, LEFT_M, 90f, 0f, 0f, 1f)
        Matrix.scaleM(matrix, LEFT_M, 1f, -1f, 1f)

        Matrix.translateM(matrix, BOTTOM_M, 0f, h.toFloat(), 0f)
        Matrix.scaleM(matrix, BOTTOM_M, 1f, -1f, 1f)

        Matrix.translateM(matrix, RIGHT_M, w.toFloat(), 0f, 0f)
        Matrix.rotateM(matrix, RIGHT_M, 90f, 0f, 0f, 1f)
    }

    override fun render(canvas: GLCanvas) {
        var more = false
        for (i in 0 until 4) {
            canvas.save(GLCanvas.SAVE_FLAG_MATRIX)
            canvas.multiplyMatrix(matrix, i * 16)
            more = more or effects[i].draw(canvas)
            canvas.restore()
        }
        if (more) {
            invalidate()
        }
    }

    // Called when the content is pulled away from the edge.
    // offset and position are in pixels. direction is one of {TOP, LEFT, BOTTOM, RIGHT}.
    fun onPull(offset: Float, position: Float, @Direction direction: Int) {
        val fullLength = if ((direction and 1) == 0) height else width
        val fullPosition = if ((direction and 1) == 0) width else height
        effects[direction].onPull(offset / fullLength, position / fullPosition)
        if (!effects[direction].isFinished) {
            invalidate()
        }
    }

    // Call when the object is released after being pulled.
    fun onRelease() {
        var more = false
        for (i in 0 until 4) {
            effects[i].onRelease()
            more = more or !effects[i].isFinished
        }
        if (more) {
            invalidate()
        }
    }

    fun onRelease(@Direction direction: Int) {
        val edgeEffect = effects[direction]
        edgeEffect.onRelease()
        if (!edgeEffect.isFinished) {
            invalidate()
        }
    }

    // Call when the effect absorbs an impact at the given velocity.
    // Used when a fling reaches the scroll boundary. velocity is in pixels
    // per second. direction is one of {TOP, LEFT, BOTTOM, RIGHT}.
    fun onAbsorb(velocity: Int, direction: Int) {
        effects[direction].onAbsorb(velocity)
        if (!effects[direction].isFinished) {
            invalidate()
        }
    }

    fun isFinished(direction: Int): Boolean = effects[direction].isFinished

    companion object {
        const val TOP = 0
        const val LEFT = 1
        const val BOTTOM = 2
        const val RIGHT = 3

        // Each edge effect has a transform matrix, and each matrix has 16 elements.
        // We put all the elements in one array. These constants specify the
        // starting index of each matrix.
        private const val TOP_M = TOP * 16
        private const val LEFT_M = LEFT * 16
        private const val BOTTOM_M = BOTTOM * 16
        private const val RIGHT_M = RIGHT * 16
    }
}
