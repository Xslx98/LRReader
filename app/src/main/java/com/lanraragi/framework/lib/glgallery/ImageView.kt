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
    "ComplexCondition",
    "NestedBlockDepth",
)

package com.lanraragi.framework.lib.glgallery

import android.graphics.Rect
import android.graphics.RectF
import com.lanraragi.framework.lib.glview.anim.AlphaAnimation
import com.lanraragi.framework.lib.glview.anim.AnimatorDurationScale
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.Texture
import com.lanraragi.framework.lib.glview.image.ImageTexture
import com.lanraragi.framework.lib.glview.image.TileRegions
import com.lanraragi.framework.lib.glview.view.GLView
import com.lanraragi.reader.util.AnimationUtils
import com.lanraragi.reader.util.MathUtils
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

internal class ImageView : GLView(), ImageTexture.Callback {

    private var mImageTexture: ImageTexture? = null
    private var mTextureWidth = 0
    private var mTextureHeight = 0

    private val mDst = RectF()
    private val mSrcActual = RectF()
    private val mDstActual = RectF()
    private val mValidRect = Rect()
    private val mScreenInTexture = RectF()
    private val mRootPosition = IntArray(2)

    private var mScaleMode = SCALE_FIT
    private var mStartPosition = START_POSITION_TOP_RIGHT
    private var mScaleValue = 1.0f

    private var mScale = 1.0f

    private var mScaleOffsetDirty = true
    private var mPositionInRootDirty = true

    private val mAlphaAnimation: AlphaAnimation = AlphaAnimation(0.0f, 1.0f)

    init {
        mAlphaAnimation.duration = ALPHA_ANIMATION_DURING
        mAlphaAnimation.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
    }

    override fun getSuggestedMinimumWidth(): Int {
        return max(
            super.getSuggestedMinimumWidth(),
            if (mImageTexture == null) 0 else mTextureWidth
        )
    }

    override fun getSuggestedMinimumHeight(): Int {
        return max(
            super.getSuggestedMinimumHeight(),
            if (mImageTexture == null) 0 else mTextureHeight
        )
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (mImageTexture == null) {
            super.onMeasure(widthSpec, heightSpec)
        } else {
            val ratio = mTextureWidth.toFloat() / mTextureHeight
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
        mScaleOffsetDirty = true
        mPositionInRootDirty = true
    }

    override fun onPositionInRootChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        mPositionInRootDirty = true

        val imageTexture = mImageTexture
        if (imageTexture != null) {
            getValidRect(mValidRect)
            if (!mValidRect.isEmpty) {
                imageTexture.start()
            } else {
                imageTexture.stop()
            }
        }
    }

    fun getScaleDefault(scaleDefault: FloatArray) {
        if (mImageTexture == null) {
            return
        }

        scaleDefault[0] = 1.0f
        scaleDefault[1] = width.toFloat() / mTextureWidth
        scaleDefault[2] = height.toFloat() / mTextureHeight
        scaleDefault[3] = max(scaleDefault[1], scaleDefault[2]) * 2

        scaleDefault[0] = MathUtils.clamp(scaleDefault[0], SCALE_MIN, SCALE_MAX)
        scaleDefault[1] = MathUtils.clamp(scaleDefault[1], SCALE_MIN, SCALE_MAX)
        scaleDefault[2] = MathUtils.clamp(scaleDefault[2], SCALE_MIN, SCALE_MAX)
        scaleDefault[3] = MathUtils.clamp(scaleDefault[3], SCALE_MIN, SCALE_MAX)

        Arrays.sort(scaleDefault)
    }

    fun setImageTexture(imageTexture: ImageTexture?) {
        // Remove callback
        val oldTexture = mImageTexture
        if (oldTexture != null) {
            oldTexture.callback = null
            oldTexture.stop()
        }

        val oldTextureWidth = mTextureWidth
        val oldTextureHeight = mTextureHeight

        mImageTexture = imageTexture

        if (imageTexture != null) {
            imageTexture.callback = this
            mTextureWidth = imageTexture.getWidth()
            mTextureHeight = imageTexture.getHeight()
            // Avoid zero and negative
            if (mTextureWidth <= 0) {
                mTextureWidth = 1
            }
            if (mTextureHeight <= 0) {
                mTextureHeight = 1
            }

            // Start alpha animation, do not show animation for image has no valid rect
            getValidRect(mValidRect)
            if (!mValidRect.isEmpty) {
                // Follows the system animator scale (0 = no fade).
                mAlphaAnimation.duration = AnimatorDurationScale.scale(ALPHA_ANIMATION_DURING)
                startAnimation(mAlphaAnimation, true)
                imageTexture.start()
            }
        } else {
            mTextureWidth = 1
            mTextureHeight = 1
        }

        mScaleOffsetDirty = true
        mPositionInRootDirty = true

        if (oldTextureWidth != mTextureWidth || oldTextureHeight != mTextureHeight) {
            requestLayout()
        }
    }

    fun getImageTexture(): ImageTexture? = mImageTexture

    fun isLoaded(): Boolean = mImageTexture != null

    fun canFlingVertically(): Boolean {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return false
            }
        }

        return mDst.top < 0.0f || mDst.bottom > height
    }

    fun canFlingHorizontally(): Boolean {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return false
            }
        }

        return mDst.left < 0.0f || mDst.right > width
    }

    fun canFling(): Boolean {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return false
            }
        }

        return mDst.left < 0.0f || mDst.top < 0.0f || mDst.right > width || mDst.bottom > height
    }

    fun getMaxDx(): Int {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return 0
            }
        }
        return max(0, -mDst.left.toInt())
    }

    fun getMinDx(): Int {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return 0
            }
        }
        return min(0, width - mDst.right.toInt())
    }

    fun getMaxDy(): Int {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return 0
            }
        }
        return max(0, -mDst.top.toInt())
    }

    fun getMinDy(): Int {
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
            if (mScaleOffsetDirty) {
                return 0
            }
        }
        return min(0, height - mDst.bottom.toInt())
    }

    fun getScale(): Float = mScale

    /**
     * Copies the current destination rect of the rendered image (view-local
     * coordinates, scale/offset applied) into `out`. Additive read-only
     * accessor for page-transform snapshots; false when no image is loaded.
     * "Loaded" here means texture-attached (`mImageTexture != null`),
     * which can lead the page's visibility state by a frame — the rect tracks
     * the texture's draw destination, not visual visibility.
     */
    @JvmName("getImageRect")
    internal fun getImageRect(out: RectF): Boolean {
        if (!isLoaded()) {
            return false
        }
        out.set(mDst)
        return true
    }

    /**
     * If target is shorter then screen, make it in screen center. If target is
     * longer then parent, make sure target fill parent over
     */
    private fun adjustPosition() {
        val dst = mDst
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
        mScaleMode = scaleMode
        mStartPosition = startPosition
        mScaleValue = scaleValue

        val screenWidth = width
        val screenHeight = height

        if (mImageTexture == null || screenWidth == 0 || screenHeight == 0) {
            mScaleOffsetDirty = true
            return
        }

        val textureWidth = mTextureWidth
        val textureHeight = mTextureHeight

        // Set scale
        var targetWidth: Float
        var targetHeight: Float
        when (scaleMode) {
            SCALE_ORIGIN -> {
                mScale = 1.0f
                targetWidth = textureWidth.toFloat()
                targetHeight = textureHeight.toFloat()
            }
            SCALE_FIT_WIDTH -> {
                mScale = screenWidth.toFloat() / textureWidth
                targetWidth = screenWidth.toFloat()
                targetHeight = textureHeight * mScale
            }
            SCALE_FIT_HEIGHT -> {
                mScale = screenHeight.toFloat() / textureHeight
                targetWidth = textureWidth * mScale
                targetHeight = screenHeight.toFloat()
            }
            SCALE_FIT -> {
                val scaleX = screenWidth.toFloat() / textureWidth
                val scaleY = screenHeight.toFloat() / textureHeight
                if (scaleX < scaleY) {
                    mScale = scaleX
                    targetWidth = screenWidth.toFloat()
                    targetHeight = textureHeight * scaleX
                } else {
                    mScale = scaleY
                    targetWidth = textureWidth * scaleY
                    targetHeight = screenHeight.toFloat()
                }
            }
            // SCALE_FIXED and anything else
            else -> {
                mScale = scaleValue
                targetWidth = textureWidth * scaleValue
                targetHeight = textureHeight * scaleValue
            }
        }

        // adjust scale, not too big, not too small
        if (mScale < SCALE_MIN) {
            mScale = SCALE_MIN
            targetWidth = textureWidth * SCALE_MIN
            targetHeight = textureHeight * SCALE_MIN
        } else if (mScale > SCALE_MAX) {
            mScale = SCALE_MAX
            targetWidth = textureWidth * SCALE_MAX
            targetHeight = textureHeight * SCALE_MAX
        }

        // Set mDst.left and mDst.right
        val dst = mDst
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

        // Set mDst.right and mDst.bottom
        dst.right = dst.left + targetWidth
        dst.bottom = dst.top + targetHeight

        // adjust position
        adjustPosition()

        mScaleOffsetDirty = false
        mPositionInRootDirty = true
    }

    fun scroll(dx: Int, dy: Int, remain: IntArray) {
        // Only work after layout
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        if (mScaleOffsetDirty) {
            remain[0] = dx
            remain[1] = dy
            return
        }

        val dst = mDst
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
            mPositionInRootDirty = true
            invalidate()
        }
    }

    fun scale(focusX: Float, focusY: Float, scale: Float) {
        // Only work after layout
        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }
        if (mScaleOffsetDirty) {
            return
        }

        if ((mScale == SCALE_MAX && scale >= 1.0f) || (mScale == SCALE_MIN && scale < 1.0f)) {
            return
        }

        var newScale = mScale * scale
        newScale = MathUtils.clamp(newScale, SCALE_MIN, SCALE_MAX)
        mScale = newScale
        val dst = mDst
        val left = (focusX - ((focusX - dst.left) * scale))
        val top = (focusY - ((focusY - dst.top) * scale))
        dst.set(
            left,
            top,
            (left + (mImageTexture!!.getWidth() * newScale)),
            (top + (mImageTexture!!.getHeight() * newScale))
        )

        // adjust position
        adjustPosition()

        mPositionInRootDirty = true
        invalidate()
    }

    private fun applyPositionInRoot() {
        val width = mImageTexture!!.getWidth()
        val height = mImageTexture!!.getHeight()
        val dst = mDst
        val dstActual = mDstActual
        val srcActual = mSrcActual

        dstActual.set(dst)
        getValidRect(mValidRect)
        if (dstActual.intersect(
                mValidRect.left.toFloat(),
                mValidRect.top.toFloat(),
                mValidRect.right.toFloat(),
                mValidRect.bottom.toFloat()
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
            getPositionInRoot(mRootPosition)
            TileRegions.screenInTexture(
                mScreenInTexture, dst, mRootPosition[0], mRootPosition[1],
                root.getWidth(), root.getHeight(), width, height
            )
        } else {
            mScreenInTexture.setEmpty()
        }
        mImageTexture!!.setScreenRegion(mScreenInTexture)

        mPositionInRootDirty = false
    }

    override fun onRender(canvas: GLCanvas) {
        val texture: Texture = mImageTexture ?: return

        if (mScaleOffsetDirty) {
            setScaleOffset(mScaleMode, mStartPosition, mScaleValue)
        }

        if (mPositionInRootDirty) {
            applyPositionInRoot()
        }

        if (!mSrcActual.isEmpty) {
            texture.draw(canvas, mSrcActual, mDstActual)
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
