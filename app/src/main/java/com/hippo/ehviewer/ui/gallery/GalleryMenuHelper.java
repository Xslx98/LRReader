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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.appcompat.widget.SwitchCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.settings.ReadingSettings;

/**
 * Constructs and manages the reading settings dialog (screen rotation,
 * reading direction, page scaling, lightness, etc.).
 * Extracted from GalleryActivity inner class to reduce its size.
 */
public class GalleryMenuHelper implements DialogInterface.OnClickListener {

    /** Callback for applying settings changes that require Activity-level access. */
    public interface SettingsCallback {
        void onSettingsApplied(int screenRotation, int layoutMode, int scaleMode,
                               int startPosition, boolean keepScreenOn,
                               boolean showClock, boolean showProgress, boolean showBattery,
                               boolean showPageInterval, boolean volumePage,
                               boolean reverseVolumePage, boolean readingFullscreen,
                               boolean customScreenLightness, int screenLightness,
                               int transferTime);
    }

    private final View mView;
    private final Spinner mScreenRotation;
    private final Spinner mReadingDirection;
    private final Spinner mScaleMode;
    private final Spinner mStartPosition;
    private final SeekBar mStartTransferTime;
    private final SwitchCompat mKeepScreenOn;
    private final SwitchCompat mShowClock;
    private final SwitchCompat mShowProgress;
    private final SwitchCompat mShowBattery;
    private final SwitchCompat mShowPageInterval;
    private final SwitchCompat mVolumePage;
    private final SwitchCompat mReverseVolumePage;
    private final SwitchCompat mReadingFullscreen;
    private final SwitchCompat mCustomScreenLightness;
    private final SeekBar mScreenLightness;

    private final SettingsCallback mCallback;

    @SuppressLint("InflateParams")
    public GalleryMenuHelper(Context context, SettingsCallback callback,
                             SeekBar.OnSeekBarChangeListener lightnessPreviewListener) {
        mCallback = callback;
        mView = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_menu, null);
        mScreenRotation = mView.findViewById(R.id.screen_rotation);
        mReadingDirection = mView.findViewById(R.id.reading_direction);
        mScaleMode = mView.findViewById(R.id.page_scaling);
        mStartPosition = mView.findViewById(R.id.start_position);
        mStartTransferTime = mView.findViewById(R.id.start_transfer_time);
        mKeepScreenOn = mView.findViewById(R.id.keep_screen_on);
        mShowClock = mView.findViewById(R.id.show_clock);
        mShowProgress = mView.findViewById(R.id.show_progress);
        mShowBattery = mView.findViewById(R.id.show_battery);
        mShowPageInterval = mView.findViewById(R.id.show_page_interval);
        mVolumePage = mView.findViewById(R.id.volume_page);
        mReverseVolumePage = mView.findViewById(R.id.reverse_volume_page);
        mReadingFullscreen = mView.findViewById(R.id.reading_fullscreen);
        mCustomScreenLightness = mView.findViewById(R.id.custom_screen_lightness);
        mScreenLightness = mView.findViewById(R.id.screen_lightness);

        // Load current values
        mScreenRotation.setSelection(ReadingSettings.getScreenRotation());
        mReadingDirection.setSelection(ReadingSettings.getReadingDirection());
        mScaleMode.setSelection(ReadingSettings.getPageScaling());
        mStartPosition.setSelection(ReadingSettings.getStartPosition());
        mStartTransferTime.setProgress(ReadingSettings.getStartTransferTime());
        mKeepScreenOn.setChecked(ReadingSettings.getKeepScreenOn());
        mShowClock.setChecked(ReadingSettings.getShowClock());
        mShowProgress.setChecked(ReadingSettings.getShowProgress());
        mShowBattery.setChecked(ReadingSettings.getShowBattery());
        mShowPageInterval.setChecked(ReadingSettings.getShowPageInterval());
        mVolumePage.setChecked(ReadingSettings.getVolumePage());
        mReverseVolumePage.setChecked(ReadingSettings.getReverseVolumePage());
        mReadingFullscreen.setChecked(ReadingSettings.getReadingFullscreen());
        mCustomScreenLightness.setChecked(ReadingSettings.getCustomScreenLightness());
        mScreenLightness.setProgress(ReadingSettings.getScreenLightness());
        mScreenLightness.setEnabled(ReadingSettings.getCustomScreenLightness());

        mVolumePage.setOnCheckedChangeListener(this::onVolumePageChange);

        if (ReadingSettings.getVolumePage()) {
            mReverseVolumePage.setVisibility(View.VISIBLE);
        } else {
            mReverseVolumePage.setVisibility(View.GONE);
        }

        mCustomScreenLightness.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mScreenLightness.setEnabled(isChecked);
        });

        // Live brightness preview
        if (lightnessPreviewListener != null) {
            mScreenLightness.setOnSeekBarChangeListener(lightnessPreviewListener);
        }
    }

    private void onVolumePageChange(CompoundButton compoundButton, boolean b) {
        if (compoundButton.isChecked()) {
            mReverseVolumePage.setVisibility(View.VISIBLE);
        } else {
            mReverseVolumePage.setVisibility(View.GONE);
        }
    }

    public View getView() {
        return mView;
    }

    public boolean isCustomScreenLightnessChecked() {
        return mCustomScreenLightness.isChecked();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        int screenRotation = mScreenRotation.getSelectedItemPosition();
        int layoutMode = com.hippo.lib.glgallery.GalleryView.sanitizeLayoutMode(
                mReadingDirection.getSelectedItemPosition());
        int scaleMode = com.hippo.lib.glgallery.GalleryView.sanitizeScaleMode(
                mScaleMode.getSelectedItemPosition());
        int startPosition = com.hippo.lib.glgallery.GalleryView.sanitizeStartPosition(
                mStartPosition.getSelectedItemPosition());
        boolean keepScreenOn = mKeepScreenOn.isChecked();
        boolean showClock = mShowClock.isChecked();
        boolean showProgress = mShowProgress.isChecked();
        boolean showBattery = mShowBattery.isChecked();
        boolean showPageInterval = mShowPageInterval.isChecked();
        boolean volumePage = mVolumePage.isChecked();
        boolean reverseVolumePage = mReverseVolumePage.isChecked();
        boolean readingFullscreen = mReadingFullscreen.isChecked();
        boolean customScreenLightness = mCustomScreenLightness.isChecked();
        int screenLightness = mScreenLightness.getProgress();
        int transferTime = mStartTransferTime.getProgress();

        // Persist all settings
        ReadingSettings.putScreenRotation(screenRotation);
        ReadingSettings.putReadingDirection(layoutMode);
        ReadingSettings.putPageScaling(scaleMode);
        ReadingSettings.putStartPosition(startPosition);
        ReadingSettings.putStartTransferTime(transferTime);
        ReadingSettings.putKeepScreenOn(keepScreenOn);
        ReadingSettings.putShowClock(showClock);
        ReadingSettings.putShowProgress(showProgress);
        ReadingSettings.putShowBattery(showBattery);
        ReadingSettings.putShowPageInterval(showPageInterval);
        ReadingSettings.putVolumePage(volumePage);
        ReadingSettings.putReadingFullscreen(readingFullscreen);
        ReadingSettings.putCustomScreenLightness(customScreenLightness);
        ReadingSettings.putScreenLightness(screenLightness);
        ReadingSettings.putReverseVolumePage(reverseVolumePage);

        // Notify Activity to apply changes
        if (mCallback != null) {
            mCallback.onSettingsApplied(screenRotation, layoutMode, scaleMode, startPosition,
                    keepScreenOn, showClock, showProgress, showBattery, showPageInterval,
                    volumePage, reverseVolumePage, readingFullscreen,
                    customScreenLightness, screenLightness, transferTime);
        }
    }
}
