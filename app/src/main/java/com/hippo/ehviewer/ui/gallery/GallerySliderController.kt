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

package com.hippo.ehviewer.ui.gallery

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.hippo.ehviewer.widget.ReversibleSeekBar
import com.hippo.lib.glgallery.GalleryView
import com.hippo.util.SystemUiHelper
import com.hippo.lib.yorozuya.AnimationUtils
import com.hippo.lib.yorozuya.SimpleAnimatorListener
import com.hippo.lib.yorozuya.SimpleHandler

/**
 * Controls the page seek bar panel and auto-transfer button animations,
 * progress text updates, and show/hide behavior.
 * Extracted from GalleryActivity to reduce its responsibility scope.
 */
class GallerySliderController : SeekBar.OnSeekBarChangeListener {

    companion object {
        private const val SLIDER_ANIMATION_DURING = 150L
        private const val HIDE_SLIDER_DELAY = 3000L
    }

    private var mSeekBarPanel: View? = null
    private var mAutoTransferPanel: ImageView? = null
    private var mLeftText: TextView? = null
    private var mRightText: TextView? = null
    private var mSeekBar: ReversibleSeekBar? = null
    private var mProgress: TextView? = null
    private var mSystemUiHelper: SystemUiHelper? = null
    private var mGalleryView: GalleryView? = null

    private var mSeekBarPanelAnimator: ObjectAnimator? = null
    private var mAutoTransferAnimator: ObjectAnimator? = null

    var layoutMode: Int = 0
        set(value) {
            field = value
            updateSlider()
        }

    var size: Int = 0
        set(value) {
            field = value
            updateSlider()
            updateProgress()
        }

    var currentIndex: Int = 0
        set(value) {
            field = value
            updateSlider()
            updateProgress()
        }

    var isShowSystemUi: Boolean = false

    private val mUpdateSliderListener = android.animation.ValueAnimator.AnimatorUpdateListener {
        mSeekBarPanel?.requestLayout()
        mAutoTransferPanel?.requestLayout()
    }

    private val mShowSliderListener = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            mSeekBarPanelAnimator = null
            mAutoTransferAnimator = null
        }
    }

    private val mHideSliderListener = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            mSeekBarPanelAnimator = null
            mSeekBarPanel?.visibility = View.INVISIBLE
            mAutoTransferAnimator = null
            mAutoTransferPanel?.visibility = View.INVISIBLE
        }
    }

    private val mHideSliderRunnable = Runnable {
        if (mSeekBarPanel != null) {
            hideSlider(mSeekBarPanel!!, mSeekBarPanelAnimator)
            hideSlider(mAutoTransferPanel, mAutoTransferAnimator)
        }
    }

    fun setViews(
        seekBarPanel: View?,
        autoTransferPanel: ImageView?,
        leftText: TextView?,
        rightText: TextView?,
        seekBar: ReversibleSeekBar?,
        progress: TextView?
    ) {
        mSeekBarPanel = seekBarPanel
        mAutoTransferPanel = autoTransferPanel
        mLeftText = leftText
        mRightText = rightText
        mSeekBar = seekBar
        mProgress = progress
        mSeekBar?.setOnSeekBarChangeListener(this)
    }

    fun setSystemUiHelper(helper: SystemUiHelper?) {
        mSystemUiHelper = helper
    }

    fun setGalleryView(galleryView: GalleryView?) {
        mGalleryView = galleryView
    }

    @SuppressLint("SetTextI18n")
    fun updateProgress() {
        val progress = mProgress ?: return
        if (size <= 0 || currentIndex < 0) {
            progress.text = null
        } else {
            progress.text = "${currentIndex + 1}/$size"
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSlider() {
        val seekBar = mSeekBar ?: return
        val rightText = mRightText ?: return
        val leftText = mLeftText ?: return
        if (size <= 0 || currentIndex < 0) return

        val start: TextView
        val end: TextView
        if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = rightText
            end = leftText
            seekBar.setReverse(true)
        } else {
            start = leftText
            end = rightText
            seekBar.setReverse(false)
        }
        start.text = (currentIndex + 1).toString()
        end.text = size.toString()
        seekBar.max = size - 1
        seekBar.progress = currentIndex
    }

    // --- SeekBar.OnSeekBarChangeListener ---

    @SuppressLint("SetTextI18n")
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        val start = if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            mRightText
        } else {
            mLeftText
        }
        if (fromUser && start != null) {
            start.text = (progress + 1).toString()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable)
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY)
        val progress = seekBar.progress
        if (progress != currentIndex && mGalleryView != null) {
            mGalleryView!!.setCurrentPage(progress)
        }
    }

    // --- Show/hide slider panel ---

    fun onTapSliderArea() {
        val seekBarPanel = mSeekBarPanel ?: return
        val autoTransferPanel = mAutoTransferPanel ?: return
        if (size <= 0 || currentIndex < 0) return

        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable)

        if (seekBarPanel.visibility == View.VISIBLE) {
            hideSlider(seekBarPanel, mSeekBarPanelAnimator)
            hideSlider(autoTransferPanel, mAutoTransferAnimator)
        } else {
            showSlider(seekBarPanel, mSeekBarPanelAnimator)
            showSlider(autoTransferPanel, mAutoTransferAnimator)
            SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY)
        }
    }

    private fun showSlider(sliderPanel: View, animator: ObjectAnimator?) {
        if (animator != null) {
            animator.cancel()
        }
        val newAnimator: ObjectAnimator
        if (sliderPanel === mAutoTransferPanel) {
            sliderPanel.translationX = sliderPanel.width.toFloat()
            newAnimator = ObjectAnimator.ofFloat(sliderPanel, "translationX", 0.0f)
        } else {
            sliderPanel.translationY = sliderPanel.height.toFloat()
            newAnimator = ObjectAnimator.ofFloat(sliderPanel, "translationY", 0.0f)
        }

        sliderPanel.visibility = View.VISIBLE

        newAnimator.duration = SLIDER_ANIMATION_DURING
        newAnimator.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        newAnimator.addUpdateListener(mUpdateSliderListener)
        newAnimator.addListener(mShowSliderListener)
        newAnimator.start()

        if (sliderPanel === mAutoTransferPanel) {
            mAutoTransferAnimator = newAnimator
        } else {
            mSeekBarPanelAnimator = newAnimator
        }

        if (mSystemUiHelper != null) {
            mSystemUiHelper!!.show()
            isShowSystemUi = true
        }
    }

    private fun hideSlider(sliderPanel: View?, @Suppress("UNUSED_PARAMETER") animator: ObjectAnimator?) {
        if (sliderPanel == null) return
        if (animator != null) {
            animator.cancel()
        }
        val newAnimator: ObjectAnimator
        if (sliderPanel === mAutoTransferPanel) {
            newAnimator = ObjectAnimator.ofFloat(sliderPanel, "translationX", sliderPanel.width.toFloat())
        } else {
            newAnimator = ObjectAnimator.ofFloat(sliderPanel, "translationY", sliderPanel.height.toFloat())
        }

        newAnimator.duration = SLIDER_ANIMATION_DURING
        newAnimator.interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
        newAnimator.addUpdateListener(mUpdateSliderListener)
        newAnimator.addListener(mHideSliderListener)
        newAnimator.start()

        if (sliderPanel === mAutoTransferPanel) {
            mAutoTransferAnimator = newAnimator
        } else {
            mSeekBarPanelAnimator = newAnimator
        }

        if (mSystemUiHelper != null) {
            mSystemUiHelper!!.hide()
            isShowSystemUi = false
        }
    }

    /** Remove pending hide-slider callbacks. Call from Activity.onDestroy(). */
    fun removeCallbacks() {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable)
    }

    /** Release view references. Call from Activity.onDestroy(). */
    fun destroy() {
        removeCallbacks()
        mSeekBarPanel = null
        mAutoTransferPanel = null
        mLeftText = null
        mRightText = null
        mSeekBar = null
        mProgress = null
    }
}
