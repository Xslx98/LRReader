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

@file:Suppress("UnsafeCallOnNullableType") // TODO(2.4-idiom)

package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.lib.glview.glrenderer.BasicTexture
import com.lanraragi.framework.lib.glview.image.GLImageMovableTextView
import com.lanraragi.framework.lib.glview.image.ImageMovableTextTexture
import com.lanraragi.framework.lib.glview.image.ImageTexture
import com.lanraragi.framework.lib.glview.view.Gravity
import com.lanraragi.framework.lib.glview.widget.GLFrameLayout
import com.lanraragi.framework.lib.glview.widget.GLLinearLayout
import com.lanraragi.framework.lib.glview.widget.GLProgressView
import com.lanraragi.framework.lib.glview.widget.GLTextureView

class GalleryPageView(
    pageTextTexture: ImageMovableTextTexture?,
    progressColor: Int,
    progressBgColor: Int,
    progressSize: Int,
    private val minHeight: Int,
    infoInterval: Int,
) : GLFrameLayout() {

    @get:JvmName("getImageView")
    internal val imageView: ImageView
    private val info: GLLinearLayout
    private val pageText: GLImageMovableTextView
    private val errorView: GLTextureView
    private val progressView: GLProgressView

    @get:JvmName("getIndex")
    @set:JvmName("setIndex")
    internal var index = INVALID_INDEX

    init {
        // Add image
        imageView = ImageView()
        var glp = GravityLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addComponent(imageView, glp)

        // Add other panel
        info = GLLinearLayout()
        info.orientation = GLLinearLayout.VERTICAL
        info.interval = infoInterval
        glp = GravityLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        glp.gravity = Gravity.CENTER
        addComponent(info, glp)

        // Add page
        pageText = GLImageMovableTextView()
        pageText.setTextTexture(pageTextTexture)
        var lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        info.addComponent(pageText, lp)

        // Add error
        errorView = GLTextureView()
        lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        info.addComponent(errorView, lp)

        // Add progress
        progressView = GLProgressView()
        progressView.setBgColor(progressBgColor)
        progressView.setColor(progressColor)
        progressView.minimumWidth = progressSize
        progressView.minimumHeight = progressSize
        lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        info.addComponent(progressView, lp)
    }

    override fun getSuggestedMinimumHeight(): Int {
        // The height of the actual image may be smaller than minHeight.
        // Set min height as 0 when the image is visible.
        // For PageLayoutManager, min height is useless.
        return if (imageView.visibility == VISIBLE) {
            0
        } else {
            minHeight
        }
    }

    fun showImage() {
        imageView.visibility = VISIBLE
        info.visibility = GONE
    }

    fun showInfo() {
        // For image valid rect
        imageView.visibility = INVISIBLE
        info.visibility = VISIBLE
    }

    private fun unbindImage() {
        val texture = imageView.imageTexture
        if (texture != null) {
            imageView.imageTexture = null
            texture.recycle()
        }
    }

    fun setImage(imageTexture: ImageTexture?) {
        unbindImage()
        if (imageTexture != null) {
            imageView.imageTexture = imageTexture
        }
    }

    fun setPage(page: Int) {
        pageText.visibility = VISIBLE
        pageText.setText(page.toString())
    }

    fun setProgress(progress: Float) {
        if (progress == PROGRESS_GONE) {
            progressView.visibility = GONE
        } else if (progress == PROGRESS_INDETERMINATE) {
            progressView.visibility = VISIBLE
            progressView.isIndeterminate = true
        } else {
            progressView.visibility = VISIBLE
            progressView.isIndeterminate = false
            progressView.setProgress(progress)
        }
    }

    private fun unbindError() {
        val texture = errorView.texture
        if (texture != null) {
            errorView.texture = null
            if (texture is BasicTexture) {
                texture.recycle()
            }
        }
    }

    fun setError(error: String?, galleryView: GalleryView?) {
        unbindError()
        if (error == null) {
            errorView.visibility = GONE
        } else {
            errorView.visibility = VISIBLE
            galleryView!!.bindErrorView(errorView, error)
        }
    }

    @get:JvmName("isLoaded")
    internal val isLoaded: Boolean
        get() = imageView.visibility == VISIBLE

    @get:JvmName("isError")
    internal val isError: Boolean
        get() = errorView.visibility == VISIBLE

    @JvmName("isUnderInfo")
    internal fun isUnderInfo(x: Float, y: Float): Boolean = info.bounds().contains(x.toInt(), y.toInt())

    companion object {
        const val INVALID_INDEX = -1

        const val PROGRESS_GONE = -1.0f
        const val PROGRESS_INDETERMINATE = -2.0f
    }
}
