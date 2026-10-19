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

package com.lanraragi.reader.ui.gallery

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.isVisible
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.lanraragi.reader.widget.ReversibleSeekBar
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.framework.util.SystemUiHelper
import android.os.Handler
import android.os.Looper
import com.lanraragi.framework.lib.yorozuya.AnimationUtils
import com.lanraragi.framework.lib.yorozuya.SimpleAnimatorListener

/**
 * Controls the reader menu — the top bar, the page seek bar panel and the
 * auto-transfer button — with its show/hide animations, plus the progress
 * text updates.
 * Extracted from GalleryActivity to reduce its responsibility scope.
 */
class GallerySliderController : SeekBar.OnSeekBarChangeListener {

    private val mainHandler = Handler(Looper.getMainLooper())

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

    private var mTopBar: View? = null

    /** Running show/hide animators, one per menu panel. */
    private val mPanelAnimators = HashMap<View, ObjectAnimator>()

    /** Whether the menu (top bar, slider and auto-turn button) is shown or showing. */
    var isMenuShown: Boolean = false
        private set

    /** Told when the menu starts showing (true) or hiding (false). */
    var onMenuVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Told the current 0-indexed page and the page count whenever either changes. */
    var onPageChanged: ((index: Int, size: Int) -> Unit)? = null

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

    private val mHideSliderRunnable = Runnable { hideMenu() }

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

    /** The reader top bar, shown and hidden with the slider. */
    fun setTopBar(topBar: View?) {
        mTopBar = topBar
    }

    fun setSystemUiHelper(helper: SystemUiHelper?) {
        mSystemUiHelper = helper
    }

    fun setGalleryView(galleryView: GalleryView?) {
        mGalleryView = galleryView
    }

    @SuppressLint("SetTextI18n")
    fun updateProgress() {
        onPageChanged?.invoke(currentIndex, size)
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
        mainHandler.removeCallbacks(mHideSliderRunnable)
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        mainHandler.postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY)
        val progress = seekBar.progress
        if (progress != currentIndex && mGalleryView != null) {
            mGalleryView!!.setCurrentPage(progress)
        }
    }

    // --- Show/hide the menu: top bar, slider panel and auto-turn button ---

    fun toggleMenu() {
        if (isMenuShown) hideMenu() else showMenu()
    }

    fun showMenu() {
        if (mSeekBarPanel == null || mAutoTransferPanel == null) return
        if (size <= 0 || currentIndex < 0) return
        mainHandler.removeCallbacks(mHideSliderRunnable)
        if (!isMenuShown) {
            isMenuShown = true
            menuPanels().forEach { animatePanel(it, show = true) }
            mSystemUiHelper?.let {
                it.show()
                isShowSystemUi = true
            }
            onMenuVisibilityChanged?.invoke(true)
        }
        mainHandler.postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY)
    }

    fun hideMenu() {
        mainHandler.removeCallbacks(mHideSliderRunnable)
        if (!isMenuShown) return
        isMenuShown = false
        menuPanels().forEach { animatePanel(it, show = false) }
        mSystemUiHelper?.let {
            it.hide()
            isShowSystemUi = false
        }
        onMenuVisibilityChanged?.invoke(false)
    }

    /** Keeps the menu up until [resumeAutoHide], e.g. while a popup from it is open. */
    fun holdAutoHide() {
        mainHandler.removeCallbacks(mHideSliderRunnable)
    }

    fun resumeAutoHide() {
        mainHandler.removeCallbacks(mHideSliderRunnable)
        if (isMenuShown) mainHandler.postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY)
    }

    private fun menuPanels(): List<View> = listOfNotNull(mTopBar, mSeekBarPanel, mAutoTransferPanel)

    /**
     * Slides [panel] in from (or out to) its own edge while fading it. A
     * reversal mid-animation starts from where the panel is now.
     */
    private fun animatePanel(panel: View, show: Boolean) {
        mPanelAnimators.remove(panel)?.cancel()
        val property = if (panel === mAutoTransferPanel) View.TRANSLATION_X else View.TRANSLATION_Y
        val hiddenOffset = when {
            panel === mAutoTransferPanel -> panel.width.toFloat()
            panel === mTopBar -> -panel.height.toFloat()
            else -> panel.height.toFloat()
        }
        if (show && !panel.isVisible) {
            property.set(panel, hiddenOffset)
            panel.alpha = 0f
        }
        panel.visibility = View.VISIBLE

        val animator = ObjectAnimator.ofPropertyValuesHolder(
            panel,
            PropertyValuesHolder.ofFloat(property, if (show) 0f else hiddenOffset),
            PropertyValuesHolder.ofFloat(View.ALPHA, if (show) 1f else 0f),
        )
        animator.duration = SLIDER_ANIMATION_DURING
        animator.interpolator = if (show) {
            AnimationUtils.FAST_SLOW_INTERPOLATOR
        } else {
            AnimationUtils.SLOW_FAST_INTERPOLATOR
        }
        if (panel !== mTopBar) animator.addUpdateListener(mUpdateSliderListener)
        animator.addListener(object : SimpleAnimatorListener() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (mPanelAnimators[panel] === animation) mPanelAnimators.remove(panel)
                if (!show && !canceled) panel.visibility = View.INVISIBLE
            }
        })
        mPanelAnimators[panel] = animator
        animator.start()
    }

    /** Remove pending hide-slider callbacks. Call from Activity.onDestroy(). */
    fun removeCallbacks() {
        mainHandler.removeCallbacks(mHideSliderRunnable)
    }

    /** Release view references. Call from Activity.onDestroy(). */
    fun destroy() {
        removeCallbacks()
        mPanelAnimators.values.toList().forEach { it.cancel() }
        mTopBar = null
        mSeekBarPanel = null
        mAutoTransferPanel = null
        mLeftText = null
        mRightText = null
        mSeekBar = null
        mProgress = null
    }
}
