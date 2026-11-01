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
 *
 * @param image the image should not be obtained or recycled.
 */
class ImageWrapper(private val image: Image) {

    private val cut: Rect = Rect(0, 0, image.width, image.height)
    private var references = 0

    // Set when the provider's cache refused the page (bigger than its whole
    // budget). Such a wrapper starts with no owner, like an animated page.
    @Volatile
    private var cacheBypassed = false

    /**
     * Cuts this image to a specified region.
     * If the region is out of the image size, clamp the region.
     */
    fun setCutRect(left: Int, top: Int, right: Int, bottom: Int) {
        cut.left = Math.max(0, left)
        cut.top = Math.max(0, top)
        cut.right = Math.min(image.width, right)
        cut.bottom = Math.min(image.height, bottom)

        if (cut.isEmpty) {
            // Empty cut has unspecified behavior
            Log.e(LOG_TAG, "Cut rect is empty")
            cut.set(0, 0, image.width, image.height)
        }
    }

    /**
     * Cuts this image to a specified region.
     * The region is described in percent, `[0.0f, 1.0f]`.
     * If the region is out of the image size, clamp the region.
     */
    fun setCutPercent(left: Float, top: Float, right: Float, bottom: Float) {
        setCutRect(
            (width * left).toInt(), (height * top).toInt(),
            (width * right).toInt(), (height * bottom).toInt()
        )
    }

    /**
     * Obtain the image
     *
     * @return false for the image is recycled and obtain failed
     */
    @Synchronized
    fun obtain(): Boolean {
        if (image.isRecycled) {
            return false
        } else {
            ++references
            return true
        }
    }

    /**
     * Release the image
     */
    @Synchronized
    fun release() {
        --references
        if (references <= 0 && !image.isRecycled) {
            image.recycle()
        }
    }

    /**
     * Marks this wrapper as not held by the provider's memory cache.
     */
    fun markCacheBypassed() {
        cacheBypassed = true
    }

    /**
     * True when no cache holds a reference to this wrapper (animated
     * pages and pages bigger than the cache budget). Whoever drops such a
     * wrapper without showing it must [release] it.
     */
    val isUncached: Boolean
        get() = cacheBypassed || animated

    val isImageRecycled: Boolean
        get() = image.isRecycled

    /**
     * @see Image.animated
     */
    val animated: Boolean
        get() = image.animated

    /**
     * The width of the cut region.
     */
    val width: Int
        get() = cut.width()

    /**
     * The height of the cut region.
     */
    val height: Int
        get() = cut.height()

    /**
     * @see Image.byteCount
     */
    val byteCount: Int
        get() = image.byteCount

    /**
     * @see Image.texImage
     */
    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        image.texImage(init, offsetX + cut.left, offsetY + cut.top, width, height)
    }

    /**
     * @see Image.start
     */
    fun start() {
        image.start()
    }

    /**
     * @see Image.advanceFrame
     */
    fun advanceFrame() {
        image.advanceFrame()
    }

    /**
     * @see Image.delay
     */
    val delay: Int
        get() = image.delay

    /**
     * @see Image.isOpaque
     */
    val isOpaque: Boolean
        get() = image.isOpaque

    /**
     * @see Image.isRecycled
     */
    val isRecycled: Boolean
        get() = image.isRecycled

    companion object {
        private const val LOG_TAG = "ImageWrapper"
    }
}
