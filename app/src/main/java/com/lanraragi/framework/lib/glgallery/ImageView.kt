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
    "LongMethod",
    "ComplexCondition",
    "NestedBlockDepth",
)

package com.lanraragi.framework.lib.glgallery

import android.graphics.Rect
import android.graphics.RectF
import com.lanraragi.framework.lib.glview.anim.AlphaAnimation
import com.lanraragi.framework.lib.glview.anim.AnimatorDurationScale
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.image.ImageTexture
import com.lanraragi.framework.lib.glview.image.TileRegions
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.reader.util.AnimationUtils
import com.lanraragi.reader.util.MathUtils
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

internal class ImageView : GLView(), ImageTexture.Callback {

    var imageTexture: ImageTexture? = null
        set(imageTexture) {
            // Remove callback
            val oldTexture = field
            if (oldTexture != null) {
                oldTexture.callback = null
                oldTexture.stop()
            }

            val oldTextureWidth = textureWidth
            val oldTextureHeight = textureHeight

            field = imageTexture

            if (imageTexture != null) {
                imageTexture.callback = this
                textureWidth = imageTexture.getWidth()
                textureHeight = imageTexture.getHeight()
                // Avoid zero and negative
                if (textureWidth <= 0) {
                    textureWidth = 1
                }
                if (textureHeight <= 0) {
                    textureHeight = 1
                }

                // Start alpha animation, do not show animation for image has no valid rect
                getValidRect(validRect)
                if (!validRect.isEmpty) {
                    // Follows the system animator scale (0 = no fade).
                    alphaAnimation.duration = AnimatorDurationScale.scale(ALPHA_ANIMATION_DURING)
                    startAnimation(alphaAnimation, true)
                    imageTexture.start()
                }
            } else {
                textureWidth = 1
                textureHeight = 1
            }

            scaleOffsetDirty = true
            positionInRootDirty = true

            if (oldTextureWidth != textureWidth || oldTextureHeight != textureHeight) {
                requestLayout()
            }
        }

    private var textureWidth = 0
    private var textureHeight = 0

    private val dstRect = RectF()
    private val srcActualRect = RectF()
    private val dstActualRect = RectF()
    private val validRect = Rect()
    private val screenInTexture = RectF()
    private val rootPosition = IntArray(2)

    private var scaleMode = SCALE_FIT
    private var startPosition = START_POSITION_TOP_RIGHT
    private var scaleValue = 1.0f

    var scale = 1.0f
        private set

    private var scaleOffsetDirty = true
    private var positionInRootDirty = true

    private val alphaAnimation: AlphaAnimation = AlphaAnimation(0.0f, 1.0f)

    init {
        alphaAnimation.duration = ALPHA_ANIMATION_DURING
        alphaAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
    }

    override fun getSuggestedMinimumWidth(): Int {
        return max(
            super.getSuggestedMinimumWidth(),
            if (imageTexture == null) 0 else textureWidth
        )
    }

    override fun getSuggestedMinimumHeight(): Int {
        return max(
            super.getSuggestedMinimumHeight(),
            if (imageTexture == null) 0 else textureHeight
        )
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (imageTexture == null) {
            super.onMeasure(widthSpec, heightSpec)
        } else {
            val ratio = textureWidth.toFloat() / textureHeight
            val widthSize = MeasureSpec.getSize(widthSpec)
            val heightSize = MeasureSpec.getSize(heightSpec)
            val widthMode = MeasureSpec.getMode(widthSpec)
            val heightMode = MeasureSpec.getMode(heightSpec)
            var measureWidth = -1
            var measureHeight = -1

            if (widthMode == MeasureSpec.EXACTLY) {
                measureWidth = widthSize
                if (heightMode == MeasureSpec.EXACTLY) {
                    measureHeight = heightSize
                } else {
                    measureHeight = (widthSize / ratio).toInt()
                    if (heightMode == MeasureSpec.AT_MOST) {
                        measureHeight = min(measureHeight, heightSize)
                    }
                }
            } else if (heightMode == MeasureSpec.EXACTLY) {
                measureHeight = heightSize
                measureWidth = (heightSize * ratio).toInt()
                if (widthMode == MeasureSpec.AT_MOST) {
                    measureWidth = min(measureWidth, widthSize)
                }
            }

            if (measureWidth == -1 || measureHeight == -1) {
                super.onMeasure(widthSpec, heightSpec)
            } else {
                setMeasuredSize(measureWidth, measureHeight)
            }
        }
    }

    override fun onSizeChanged(newW: Int, newH: Int, oldW: Int, oldH: Int) {
        scaleOffsetDirty = true
        positionInRootDirty = true
    }

    override fun onPositionInRootChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        positionInRootDirty = true

        val texture = imageTexture
        if (texture != null) {
            getValidRect(validRect)
            if (!validRect.isEmpty) {
                texture.start()
            } else {
                texture.stop()
            }
        }
    }

    fun getScaleDefault(scaleDefault: FloatArray) {
        if (imageTexture == null) {
            return
        }

        scaleDefault[0] = 1.0f
        scaleDefault[1] = width.toFloat() / textureWidth
        scaleDefault[2] = height.toFloat() / textureHeight
        scaleDefault[3] = max(scaleDefault[1], scaleDefault[2]) * 2

        scaleDefault[0] = MathUtils.clamp(scaleDefault[0], SCALE_MIN, SCALE_MAX)
        scaleDefault[1] = MathUtils.clamp(scaleDefault[1], SCALE_MIN, SCALE_MAX)
        scaleDefault[2] = MathUtils.clamp(scaleDefault[2], SCALE_MIN, SCALE_MAX)
        scaleDefault[3] = MathUtils.clamp(scaleDefault[3], SCALE_MIN, SCALE_MAX)

        Arrays.sort(scaleDefault)
    }

    val isLoaded: Boolean
        get() = imageTexture != null

    fun canFlingVertically(): Boolean {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return false
            }
        }

        return dstRect.top < 0.0f || dstRect.bottom > height
    }

    fun canFlingHorizontally(): Boolean {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return false
            }
        }

        return dstRect.left < 0.0f || dstRect.right > width
    }

    fun canFling(): Boolean {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return false
            }
        }

        return dstRect.left < 0.0f || dstRect.top < 0.0f || dstRect.right > width || dstRect.bottom > height
    }

    fun getMaxDx(): Int {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return 0
            }
        }
        return max(0, -dstRect.left.toInt())
    }

    fun getMinDx(): Int {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return 0
            }
        }
        return min(0, width - dstRect.right.toInt())
    }

    fun getMaxDy(): Int {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return 0
            }
        }
        return max(0, -dstRect.top.toInt())
    }

    fun getMinDy(): Int {
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
            if (scaleOffsetDirty) {
                return 0
            }
        }
        return min(0, height - dstRect.bottom.toInt())
    }

    /**
     * Copies the current destination rect of the rendered image (view-local
     * coordinates, scale/offset applied) into `out`. Additive read-only
     * accessor for page-transform snapshots; false when no image is loaded.
     * "Loaded" here means texture-attached (`imageTexture != null`),
     * which can lead the page's visibility state by a frame — the rect tracks
     * the texture's draw destination, not visual visibility.
     */
    @JvmName("getImageRect")
    internal fun getImageRect(out: RectF): Boolean {
        if (!isLoaded) {
            return false
        }
        out.set(dstRect)
        return true
    }

    /**
     * If target is shorter then screen, make it in screen center. If target is
     * longer then parent, make sure target fill parent over
     */
    private fun adjustPosition() {
        val dst = dstRect
        val screenWidth = width
        val screenHeight = height
        val targetWidth = dst.width()
        val targetHeight = dst.height()

        if (targetWidth > screenWidth) {
            var fixXOffset = dst.left
            if (fixXOffset > 0) {
                dst.left -= fixXOffset
                dst.right -= fixXOffset
            } else {
                fixXOffset = screenWidth - dst.right
                if (fixXOffset > 0) {
                    dst.left += fixXOffset
                    dst.right += fixXOffset
                }
            }
        } else {
            val left = (screenWidth - targetWidth) / 2
            dst.offsetTo(left, dst.top)
        }
        if (targetHeight > screenHeight) {
            var fixYOffset = dst.top
            if (fixYOffset > 0) {
                dst.top -= fixYOffset
                dst.bottom -= fixYOffset
            } else {
                fixYOffset = screenHeight - dst.bottom
                if (fixYOffset > 0) {
                    dst.top += fixYOffset
                    dst.bottom += fixYOffset
                }
            }
        } else {
            val top = (screenHeight - targetHeight) / 2
            dst.offsetTo(dst.left, top)
        }
    }

    fun setScaleOffset(scaleMode: Int, startPosition: Int, scaleValue: Float) {
        this.scaleMode = scaleMode
        this.startPosition = startPosition
        this.scaleValue = scaleValue

        val screenWidth = width
        val screenHeight = height

        if (imageTexture == null || screenWidth == 0 || screenHeight == 0) {
            scaleOffsetDirty = true
            return
        }

        val texWidth = textureWidth
        val texHeight = textureHeight

        // Set scale
        var targetWidth: Float
        var targetHeight: Float
        when (scaleMode) {
            SCALE_ORIGIN -> {
                scale = 1.0f
                targetWidth = texWidth.toFloat()
                targetHeight = texHeight.toFloat()
            }
            SCALE_FIT_WIDTH -> {
                scale = screenWidth.toFloat() / texWidth
                targetWidth = screenWidth.toFloat()
                targetHeight = texHeight * scale
            }
            SCALE_FIT_HEIGHT -> {
                scale = screenHeight.toFloat() / texHeight
                targetWidth = texWidth * scale
                targetHeight = screenHeight.toFloat()
            }
            SCALE_FIT -> {
                val scaleX = screenWidth.toFloat() / texWidth
                val scaleY = screenHeight.toFloat() / texHeight
                if (scaleX < scaleY) {
                    scale = scaleX
                    targetWidth = screenWidth.toFloat()
                    targetHeight = texHeight * scaleX
                } else {
                    scale = scaleY
                    targetWidth = texWidth * scaleY
                    targetHeight = screenHeight.toFloat()
                }
            }
            // SCALE_FIXED and anything else
            else -> {
                scale = scaleValue
                targetWidth = texWidth * scaleValue
                targetHeight = texHeight * scaleValue
            }
        }

        // adjust scale, not too big, not too small
        if (scale < SCALE_MIN) {
            scale = SCALE_MIN
            targetWidth = texWidth * SCALE_MIN
            targetHeight = texHeight * SCALE_MIN
        } else if (scale > SCALE_MAX) {
            scale = SCALE_MAX
            targetWidth = texWidth * SCALE_MAX
            targetHeight = texHeight * SCALE_MAX
        }

        // Set dstRect.left and dstRect.top
        val dst = dstRect
        when (startPosition) {
            START_POSITION_TOP_LEFT -> {
                dst.left = 0f
                dst.top = 0f
            }
            START_POSITION_TOP_RIGHT -> {
                dst.left = screenWidth - targetWidth
                dst.top = 0f
            }
            START_POSITION_BOTTOM_LEFT -> {
                dst.left = 0f
                dst.top = screenHeight - targetHeight
            }
            START_POSITION_BOTTOM_RIGHT -> {
                dst.left = screenWidth - targetWidth
                dst.top = screenHeight - targetHeight
            }
            // START_POSITION_CENTER and anything else
            else -> {
                dst.left = (screenWidth - targetWidth) / 2
                dst.top = (screenHeight - targetHeight) / 2
            }
        }

        // Set dstRect.right and dstRect.bottom
        dst.right = dst.left + targetWidth
        dst.bottom = dst.top + targetHeight

        // adjust position
        adjustPosition()

        scaleOffsetDirty = false
        positionInRootDirty = true
    }

    fun scroll(dx: Int, dy: Int, remain: IntArray) {
        // Only work after layout
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
        }
        if (scaleOffsetDirty) {
            remain[0] = dx
            remain[1] = dy
            return
        }

        val dst = dstRect
        val screenWidth = width
        val screenHeight = height
        val targetWidth = dst.width()
        val targetHeight = dst.height()

        if (targetWidth > screenWidth) {
            dst.left -= dx
            dst.right -= dx

            var fixXOffset = dst.left
            if (fixXOffset > 0) {
                dst.left -= fixXOffset
                dst.right -= fixXOffset
                remain[0] = -fixXOffset.toInt()
            } else {
                fixXOffset = screenWidth - dst.right
                if (fixXOffset > 0) {
                    dst.left += fixXOffset
                    dst.right += fixXOffset
                    remain[0] = fixXOffset.toInt()
                } else {
                    remain[0] = 0
                }
            }
        } else {
            remain[0] = dx
        }
        if (targetHeight > screenHeight) {
            dst.top -= dy
            dst.bottom -= dy

            var fixYOffset = dst.top
            if (fixYOffset > 0) {
                dst.top -= fixYOffset
                dst.bottom -= fixYOffset
                remain[1] = -fixYOffset.toInt()
            } else {
                fixYOffset = screenHeight - dst.bottom
                if (fixYOffset > 0) {
                    dst.top += fixYOffset
                    dst.bottom += fixYOffset
                    remain[1] = fixYOffset.toInt()
                } else {
                    remain[1] = 0
                }
            }
        } else {
            remain[1] = dy
        }

        if (dx != remain[0] || dy != remain[1]) {
            positionInRootDirty = true
            invalidate()
        }
    }

    fun scale(focusX: Float, focusY: Float, scale: Float) {
        // Only work after layout
        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
        }
        if (scaleOffsetDirty) {
            return
        }
        // A clean scale offset means setScaleOffset saw a texture.
        val texture = imageTexture ?: return

        if ((this.scale == SCALE_MAX && scale >= 1.0f) || (this.scale == SCALE_MIN && scale < 1.0f)) {
            return
        }

        var newScale = this.scale * scale
        newScale = MathUtils.clamp(newScale, SCALE_MIN, SCALE_MAX)
        this.scale = newScale
        val dst = dstRect
        val left = (focusX - ((focusX - dst.left) * scale))
        val top = (focusY - ((focusY - dst.top) * scale))
        dst.set(
            left,
            top,
            (left + (texture.getWidth() * newScale)),
            (top + (texture.getHeight() * newScale))
        )

        // adjust position
        adjustPosition()

        positionInRootDirty = true
        invalidate()
    }

    private fun applyPositionInRoot(texture: ImageTexture) {
        val width = texture.getWidth()
        val height = texture.getHeight()
        val dst = dstRect
        val dstActual = dstActualRect
        val srcActual = srcActualRect

        dstActual.set(dst)
        getValidRect(validRect)
        if (dstActual.intersect(
                validRect.left.toFloat(),
                validRect.top.toFloat(),
                validRect.right.toFloat(),
                validRect.bottom.toFloat()
            )
        ) {
            // The int lerp overload, as in Java: the source edges are whole texels.
            srcActual.left = MathUtils.lerp(
                0,
                width,
                MathUtils.delerp(dst.left, dst.right, dstActual.left)
            ).toFloat()
            srcActual.right = MathUtils.lerp(
                0,
                width,
                MathUtils.delerp(dst.left, dst.right, dstActual.right)
            ).toFloat()
            srcActual.top = MathUtils.lerp(
                0,
                height,
                MathUtils.delerp(dst.top, dst.bottom, dstActual.top)
            ).toFloat()
            srcActual.bottom = MathUtils.lerp(
                0,
                height,
                MathUtils.delerp(dst.top, dst.bottom, dstActual.bottom)
            ).toFloat()
        } else {
            // Can't be seen, set src and dst empty
            srcActual.setEmpty()
            dstActual.setEmpty()
        }

        // Tell the texture where the screen is, so it uploads the tiles near
        // it first and releases those far away (audit 2026-10-13 PERF-02).
        val root = getGLRoot()
        if (root != null) {
            getPositionInRoot(rootPosition)
            TileRegions.screenInTexture(
                screenInTexture, dst, rootPosition[0], rootPosition[1],
                root.getWidth(), root.getHeight(), width, height
            )
        } else {
            screenInTexture.setEmpty()
        }
        texture.setScreenRegion(screenInTexture)

        positionInRootDirty = false
    }

    override fun onRender(canvas: GLCanvas) {
        val texture = imageTexture ?: return

        if (scaleOffsetDirty) {
            setScaleOffset(scaleMode, startPosition, scaleValue)
        }

        if (positionInRootDirty) {
            applyPositionInRoot(texture)
        }

        if (!srcActualRect.isEmpty) {
            texture.draw(canvas, srcActualRect, dstActualRect)
        }
    }

    override fun invalidateImageTexture(who: ImageTexture) {
        invalidate()
    }

    companion object {
        // UPSTREAM-LEGACY: scale limits are currently hardcoded, sufficient for all use cases
        private const val SCALE_MIN = 1 / 10.0f
        private const val SCALE_MAX = 10.0f

        const val SCALE_ORIGIN = 0
        const val SCALE_FIT_WIDTH = 1
        const val SCALE_FIT_HEIGHT = 2
        const val SCALE_FIT = 3
        const val SCALE_FIXED = 4

        const val START_POSITION_TOP_LEFT = 0
        const val START_POSITION_TOP_RIGHT = 1
        const val START_POSITION_BOTTOM_LEFT = 2
        const val START_POSITION_BOTTOM_RIGHT = 3
        const val START_POSITION_CENTER = 4

        private const val ALPHA_ANIMATION_DURING = 300L
    }
}
