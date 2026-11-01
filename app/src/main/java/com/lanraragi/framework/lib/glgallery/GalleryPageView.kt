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
    minHeight: Int,
    infoInterval: Int,
) : GLFrameLayout() {

    private val mImage: ImageView
    private val mInfo: GLLinearLayout
    private val mPage: GLImageMovableTextView
    private val mError: GLTextureView
    private val mProgress: GLProgressView

    private val mMinHeight: Int

    private var mIndex = INVALID_INDEX

    init {
        // Add image
        mImage = ImageView()
        var glp = GravityLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addComponent(mImage, glp)

        // Add other panel
        mInfo = GLLinearLayout()
        mInfo.orientation = GLLinearLayout.VERTICAL
        mInfo.interval = infoInterval
        glp = GravityLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        glp.gravity = Gravity.CENTER
        addComponent(mInfo, glp)

        // Add page
        mPage = GLImageMovableTextView()
        mPage.setTextTexture(pageTextTexture)
        var lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        mInfo.addComponent(mPage, lp)

        // Add error
        mError = GLTextureView()
        lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        mInfo.addComponent(mError, lp)

        // Add progress
        mProgress = GLProgressView()
        mProgress.setBgColor(progressBgColor)
        mProgress.setColor(progressColor)
        mProgress.minimumWidth = progressSize
        mProgress.minimumHeight = progressSize
        lp = GLLinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        mInfo.addComponent(mProgress, lp)

        mMinHeight = minHeight
    }

    override fun getSuggestedMinimumHeight(): Int {
        // The height of the actual image may be smaller than mPageMinHeight.
        // Set min height as 0 when the image is visible.
        // For PageLayoutManager, min height is useless.
        return if (mImage.visibility == VISIBLE) {
            0
        } else {
            mMinHeight
        }
    }

    @JvmName("getIndex")
    internal fun getIndex(): Int = mIndex

    @JvmName("setIndex")
    internal fun setIndex(index: Int) {
        mIndex = index
    }

    fun showImage() {
        mImage.visibility = VISIBLE
        mInfo.visibility = GONE
    }

    fun showInfo() {
        // For image valid rect
        mImage.visibility = INVISIBLE
        mInfo.visibility = VISIBLE
    }

    private fun unbindImage() {
        val texture = mImage.getImageTexture()
        if (texture != null) {
            mImage.setImageTexture(null)
            texture.recycle()
        }
    }

    fun setImage(imageTexture: ImageTexture?) {
        unbindImage()
        if (imageTexture != null) {
            mImage.setImageTexture(imageTexture)
        }
    }

    fun setPage(page: Int) {
        mPage.visibility = VISIBLE
        mPage.setText(page.toString())
    }

    fun setProgress(progress: Float) {
        if (progress == PROGRESS_GONE) {
            mProgress.visibility = GONE
        } else if (progress == PROGRESS_INDETERMINATE) {
            mProgress.visibility = VISIBLE
            mProgress.isIndeterminate = true
        } else {
            mProgress.visibility = VISIBLE
            mProgress.isIndeterminate = false
            mProgress.setProgress(progress)
        }
    }

    private fun unbindError() {
        val texture = mError.texture
        if (texture != null) {
            mError.texture = null
            if (texture is BasicTexture) {
                texture.recycle()
            }
        }
    }

    fun setError(error: String?, galleryView: GalleryView?) {
        unbindError()
        if (error == null) {
            mError.visibility = GONE
        } else {
            mError.visibility = VISIBLE
            galleryView!!.bindErrorView(mError, error)
        }
    }

    @JvmName("getImageView")
    internal fun getImageView(): ImageView = mImage

    @JvmName("isLoaded")
    internal fun isLoaded(): Boolean = mImage.visibility == VISIBLE

    @JvmName("isError")
    internal fun isError(): Boolean = mError.visibility == VISIBLE

    @JvmName("isUnderInfo")
    internal fun isUnderInfo(x: Float, y: Float): Boolean = mInfo.bounds().contains(x.toInt(), y.toInt())

    companion object {
        const val INVALID_INDEX = -1

        const val PROGRESS_GONE = -1.0f
        const val PROGRESS_INDETERMINATE = -2.0f
    }
}
