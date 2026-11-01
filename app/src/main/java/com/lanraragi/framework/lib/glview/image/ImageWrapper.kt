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

package com.lanraragi.framework.lib.glview.image

import android.graphics.Rect
import android.util.Log
import com.lanraragi.framework.lib.image.Image

/**
 * A wrapper for [Image]. It is useful for multi-usage.
 * It handles image recycle automatically.
 */
class ImageWrapper(
    /**
     * the image should not be obtained or recycled.
     */
    image: Image,
) {

    private val mImage: Image = image
    private val mCut: Rect = Rect(0, 0, image.width, image.height)
    private var mReferences = 0

    // Set when the provider's cache refused the page (bigger than its whole
    // budget). Such a wrapper starts with no owner, like an animated page.
    @Volatile
    private var mCacheBypassed = false

    /**
     * Cuts this image to a specified region.
     * If the region is out of the image size, clamp the region.
     */
    fun setCutRect(left: Int, top: Int, right: Int, bottom: Int) {
        mCut.left = Math.max(0, left)
        mCut.top = Math.max(0, top)
        mCut.right = Math.min(mImage.width, right)
        mCut.bottom = Math.min(mImage.height, bottom)

        if (mCut.isEmpty) {
            // Empty mCut has unspecified behavior
            Log.e(LOG_TAG, "Cut rect is empty")
            mCut.set(0, 0, mImage.width, mImage.height)
        }
    }

    /**
     * Cuts this image to a specified region.
     * The region is described in percent, `[0.0f, 1.0f]`.
     * If the region is out of the image size, clamp the region.
     */
    fun setCutPercent(left: Float, top: Float, right: Float, bottom: Float) {
        setCutRect(
            (getWidth() * left).toInt(), (getHeight() * top).toInt(),
            (getWidth() * right).toInt(), (getHeight() * bottom).toInt()
        )
    }

    /**
     * Obtain the image
     *
     * @return false for the image is recycled and obtain failed
     */
    @Synchronized
    fun obtain(): Boolean {
        if (mImage.isRecycled) {
            return false
        } else {
            ++mReferences
            return true
        }
    }

    /**
     * Release the image
     */
    @Synchronized
    fun release() {
        --mReferences
        if (mReferences <= 0 && !mImage.isRecycled) {
            mImage.recycle()
        }
    }

    /**
     * Marks this wrapper as not held by the provider's memory cache.
     */
    fun markCacheBypassed() {
        mCacheBypassed = true
    }

    /**
     * @return true when no cache holds a reference to this wrapper (animated
     * pages and pages bigger than the cache budget). Whoever drops such a
     * wrapper without showing it must [release] it.
     */
    // Properties, not functions: Kotlin callers (GalleryProviderOversizedPageTest)
    // read the Java getters with property syntax.
    val isUncached: Boolean
        get() = mCacheBypassed || getAnimated()

    val isImageRecycled: Boolean
        get() = mImage.isRecycled

    /**
     * @see Image.animated
     */
    fun getAnimated(): Boolean {
        return mImage.animated
    }

    /**
     * @see Image.width
     */
    fun getWidth(): Int {
        return mCut.width()
    }

    /**
     * @see Image.height
     */
    fun getHeight(): Int {
        return mCut.height()
    }

    /**
     * @see Image.byteCount
     */
    fun getByteCount(): Int {
        return mImage.byteCount
    }

    /**
     * @see Image.texImage
     */
    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        mImage.texImage(init, offsetX + mCut.left, offsetY + mCut.top, width, height)
    }

    /**
     * @see Image.start
     */
    fun start() {
        mImage.start()
    }

    /**
     * @see Image.advanceFrame
     */
    fun advanceFrame() {
        mImage.advanceFrame()
    }

    /**
     * @see Image.delay
     */
    fun getDelay(): Int {
        return mImage.delay
    }

    /**
     * @see Image.isOpaque
     */
    fun isOpaque(): Boolean {
        return mImage.isOpaque
    }

    /**
     * @see Image.isRecycled
     */
    fun isRecycled(): Boolean {
        return mImage.isRecycled
    }

    companion object {
        private const val LOG_TAG = "ImageWrapper"
    }
}
