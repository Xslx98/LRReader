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

package com.hippo.ehviewer.ui.gallery;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.util.SystemUiHelper;
import com.hippo.lib.yorozuya.AnimationUtils;
import com.hippo.lib.yorozuya.SimpleAnimatorListener;
import com.hippo.lib.yorozuya.SimpleHandler;

import android.annotation.SuppressLint;

/**
 * Controls the page seek bar panel and auto-transfer button animations,
 * progress text updates, and show/hide behavior.
 * Extracted from GalleryActivity to reduce its responsibility scope.
 */
public class GallerySliderController implements SeekBar.OnSeekBarChangeListener {

    private static final long SLIDER_ANIMATION_DURING = 150;
    private static final long HIDE_SLIDER_DELAY = 3000;

    @Nullable private View mSeekBarPanel;
    @Nullable private ImageView mAutoTransferPanel;
    @Nullable private TextView mLeftText;
    @Nullable private TextView mRightText;
    @Nullable private ReversibleSeekBar mSeekBar;
    @Nullable private TextView mProgress;
    @Nullable private SystemUiHelper mSystemUiHelper;
    @Nullable private GalleryView mGalleryView;

    private ObjectAnimator mSeekBarPanelAnimator;
    private ObjectAnimator mAutoTransferAnimator;

    private int mLayoutMode;
    private int mSize;
    private int mCurrentIndex;

    private boolean mShowSystemUi;

    private final ValueAnimator.AnimatorUpdateListener mUpdateSliderListener = animation -> {
        if (null != mSeekBarPanel) {
            mSeekBarPanel.requestLayout();
        }
        if (null != mAutoTransferPanel) {
            mAutoTransferPanel.requestLayout();
        }
    };

    private final SimpleAnimatorListener mShowSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            mAutoTransferAnimator = null;
        }
    };

    private final SimpleAnimatorListener mHideSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            if (mSeekBarPanel != null) {
                mSeekBarPanel.setVisibility(View.INVISIBLE);
            }
            mAutoTransferAnimator = null;
            if (mAutoTransferPanel != null) {
                mAutoTransferPanel.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final Runnable mHideSliderRunnable = () -> {
        if (mSeekBarPanel != null) {
            hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
            hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
        }
    };

    public void setViews(@Nullable View seekBarPanel, @Nullable ImageView autoTransferPanel,
                         @Nullable TextView leftText, @Nullable TextView rightText,
                         @Nullable ReversibleSeekBar seekBar, @Nullable TextView progress) {
        mSeekBarPanel = seekBarPanel;
        mAutoTransferPanel = autoTransferPanel;
        mLeftText = leftText;
        mRightText = rightText;
        mSeekBar = seekBar;
        mProgress = progress;
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(this);
        }
    }

    public void setSystemUiHelper(@Nullable SystemUiHelper helper) {
        mSystemUiHelper = helper;
    }

    public void setGalleryView(@Nullable GalleryView galleryView) {
        mGalleryView = galleryView;
    }

    public void setLayoutMode(int layoutMode) {
        mLayoutMode = layoutMode;
        updateSlider();
    }

    public void setSize(int size) {
        mSize = size;
        updateSlider();
        updateProgress();
    }

    public void setCurrentIndex(int index) {
        mCurrentIndex = index;
        updateSlider();
        updateProgress();
    }

    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    public int getLayoutMode() {
        return mLayoutMode;
    }

    public boolean isShowSystemUi() {
        return mShowSystemUi;
    }

    public void setShowSystemUi(boolean show) {
        mShowSystemUi = show;
    }

    @SuppressLint("SetTextI18n")
    public void updateProgress() {
        if (mProgress == null) {
            return;
        }
        if (mSize <= 0 || mCurrentIndex < 0) {
            mProgress.setText(null);
        } else {
            mProgress.setText((mCurrentIndex + 1) + "/" + mSize);
        }
    }

    @SuppressLint("SetTextI18n")
    public void updateSlider() {
        if (mSeekBar == null || mRightText == null || mLeftText == null || mSize <= 0 || mCurrentIndex < 0) {
            return;
        }

        TextView start;
        TextView end;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
            end = mLeftText;
            mSeekBar.setReverse(true);
        } else {
            start = mLeftText;
            end = mRightText;
            mSeekBar.setReverse(false);
        }
        start.setText(Integer.toString(mCurrentIndex + 1));
        end.setText(Integer.toString(mSize));
        mSeekBar.setMax(mSize - 1);
        mSeekBar.setProgress(mCurrentIndex);
    }

    // --- SeekBar.OnSeekBarChangeListener ---

    @Override
    @SuppressLint("SetTextI18n")
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        TextView start;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
        } else {
            start = mLeftText;
        }
        if (fromUser && null != start) {
            start.setText(Integer.toString(progress + 1));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
        int progress = seekBar.getProgress();
        if (progress != mCurrentIndex && null != mGalleryView) {
            mGalleryView.setCurrentPage(progress);
        }
    }

    // --- Show/hide slider panel ---

    public void onTapSliderArea() {
        if (mSeekBarPanel == null || mSize <= 0 || mCurrentIndex < 0 || mAutoTransferPanel == null) {
            return;
        }

        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);

        if (mSeekBarPanel.getVisibility() == View.VISIBLE) {
            hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
            hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
        } else {
            showSlider(mSeekBarPanel, mSeekBarPanelAnimator);
            showSlider(mAutoTransferPanel, mAutoTransferAnimator);
            SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
        }
    }

    private void showSlider(View sliderPanel, ObjectAnimator animator) {
        if (null != mSeekBarPanelAnimator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel) {
            sliderPanel.setTranslationX(sliderPanel.getWidth());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", 0.0f);
        } else {
            sliderPanel.setTranslationY(sliderPanel.getHeight());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", 0.0f);
        }

        sliderPanel.setVisibility(View.VISIBLE);

        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mShowSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.show();
            mShowSystemUi = true;
        }
    }

    private void hideSlider(View sliderPanel, ObjectAnimator animator) {
        if (null != animator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel) {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", sliderPanel.getWidth());
        } else {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", sliderPanel.getHeight());
        }

        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mHideSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.hide();
            mShowSystemUi = false;
        }
    }

    /** Remove pending hide-slider callbacks. Call from Activity.onDestroy(). */
    public void removeCallbacks() {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
    }

    /** Release view references. Call from Activity.onDestroy(). */
    public void destroy() {
        removeCallbacks();
        mSeekBarPanel = null;
        mAutoTransferPanel = null;
        mLeftText = null;
        mRightText = null;
        mSeekBar = null;
        mProgress = null;
    }
}
