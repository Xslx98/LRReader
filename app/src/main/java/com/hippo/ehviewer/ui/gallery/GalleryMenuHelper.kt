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

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.ReadingSettings

/**
 * Constructs and manages the reading settings dialog (screen rotation,
 * reading direction, page scaling, lightness, etc.).
 * Extracted from GalleryActivity inner class to reduce its size.
 */
class GalleryMenuHelper @SuppressLint("InflateParams") constructor(
    context: Context,
    private val mCallback: SettingsCallback?,
    lightnessPreviewListener: SeekBar.OnSeekBarChangeListener?
) : DialogInterface.OnClickListener {

    /** Callback for applying settings changes that require Activity-level access. */
    interface SettingsCallback {
        fun onSettingsApplied(
            screenRotation: Int, layoutMode: Int, scaleMode: Int,
            startPosition: Int, keepScreenOn: Boolean,
            showClock: Boolean, showProgress: Boolean, showBattery: Boolean,
            showPageInterval: Boolean, volumePage: Boolean,
            reverseVolumePage: Boolean, readingFullscreen: Boolean,
            customScreenLightness: Boolean, screenLightness: Int,
            transferTime: Int
        )
    }

    val view: View
    private val mScreenRotation: Spinner
    private val mReadingDirection: Spinner
    private val mScaleMode: Spinner
    private val mStartPosition: Spinner
    private val mStartTransferTime: SeekBar
    private val mKeepScreenOn: SwitchCompat
    private val mShowClock: SwitchCompat
    private val mShowProgress: SwitchCompat
    private val mShowBattery: SwitchCompat
    private val mShowPageInterval: SwitchCompat
    private val mVolumePage: SwitchCompat
    private val mReverseVolumePage: SwitchCompat
    private val mReadingFullscreen: SwitchCompat
    private val mCustomScreenLightness: SwitchCompat
    private val mScreenLightness: SeekBar

    val isCustomScreenLightnessChecked: Boolean
        get() = mCustomScreenLightness.isChecked

    init {
        view = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_menu, null)
        mScreenRotation = view.findViewById(R.id.screen_rotation)
        mReadingDirection = view.findViewById(R.id.reading_direction)
        mScaleMode = view.findViewById(R.id.page_scaling)
        mStartPosition = view.findViewById(R.id.start_position)
        mStartTransferTime = view.findViewById(R.id.start_transfer_time)
        mKeepScreenOn = view.findViewById(R.id.keep_screen_on)
        mShowClock = view.findViewById(R.id.show_clock)
        mShowProgress = view.findViewById(R.id.show_progress)
        mShowBattery = view.findViewById(R.id.show_battery)
        mShowPageInterval = view.findViewById(R.id.show_page_interval)
        mVolumePage = view.findViewById(R.id.volume_page)
        mReverseVolumePage = view.findViewById(R.id.reverse_volume_page)
        mReadingFullscreen = view.findViewById(R.id.reading_fullscreen)
        mCustomScreenLightness = view.findViewById(R.id.custom_screen_lightness)
        mScreenLightness = view.findViewById(R.id.screen_lightness)

        // Load current values
        mScreenRotation.setSelection(ReadingSettings.getScreenRotation())
        mReadingDirection.setSelection(ReadingSettings.getReadingDirection())
        mScaleMode.setSelection(ReadingSettings.getPageScaling())
        mStartPosition.setSelection(ReadingSettings.getStartPosition())
        mStartTransferTime.progress = ReadingSettings.getStartTransferTime()
        mKeepScreenOn.isChecked = ReadingSettings.getKeepScreenOn()
        mShowClock.isChecked = ReadingSettings.getShowClock()
        mShowProgress.isChecked = ReadingSettings.getShowProgress()
        mShowBattery.isChecked = ReadingSettings.getShowBattery()
        mShowPageInterval.isChecked = ReadingSettings.getShowPageInterval()
        mVolumePage.isChecked = ReadingSettings.getVolumePage()
        mReverseVolumePage.isChecked = ReadingSettings.getReverseVolumePage()
        mReadingFullscreen.isChecked = ReadingSettings.getReadingFullscreen()
        mCustomScreenLightness.isChecked = ReadingSettings.getCustomScreenLightness()
        mScreenLightness.progress = ReadingSettings.getScreenLightness()
        mScreenLightness.isEnabled = ReadingSettings.getCustomScreenLightness()

        mVolumePage.setOnCheckedChangeListener { _, isChecked ->
            mReverseVolumePage.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        mReverseVolumePage.visibility = if (ReadingSettings.getVolumePage()) View.VISIBLE else View.GONE

        mCustomScreenLightness.setOnCheckedChangeListener { _, isChecked ->
            mScreenLightness.isEnabled = isChecked
        }

        // Live brightness preview
        if (lightnessPreviewListener != null) {
            mScreenLightness.setOnSeekBarChangeListener(lightnessPreviewListener)
        }
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        val screenRotation = mScreenRotation.selectedItemPosition
        val layoutMode = com.hippo.lib.glgallery.GalleryView.sanitizeLayoutMode(
            mReadingDirection.selectedItemPosition
        )
        val scaleMode = com.hippo.lib.glgallery.GalleryView.sanitizeScaleMode(
            mScaleMode.selectedItemPosition
        )
        val startPosition = com.hippo.lib.glgallery.GalleryView.sanitizeStartPosition(
            mStartPosition.selectedItemPosition
        )
        val keepScreenOn = mKeepScreenOn.isChecked
        val showClock = mShowClock.isChecked
        val showProgress = mShowProgress.isChecked
        val showBattery = mShowBattery.isChecked
        val showPageInterval = mShowPageInterval.isChecked
        val volumePage = mVolumePage.isChecked
        val reverseVolumePage = mReverseVolumePage.isChecked
        val readingFullscreen = mReadingFullscreen.isChecked
        val customScreenLightness = mCustomScreenLightness.isChecked
        val screenLightness = mScreenLightness.progress
        val transferTime = mStartTransferTime.progress

        // Persist all settings
        ReadingSettings.putScreenRotation(screenRotation)
        ReadingSettings.putReadingDirection(layoutMode)
        ReadingSettings.putPageScaling(scaleMode)
        ReadingSettings.putStartPosition(startPosition)
        ReadingSettings.putStartTransferTime(transferTime)
        ReadingSettings.putKeepScreenOn(keepScreenOn)
        ReadingSettings.putShowClock(showClock)
        ReadingSettings.putShowProgress(showProgress)
        ReadingSettings.putShowBattery(showBattery)
        ReadingSettings.putShowPageInterval(showPageInterval)
        ReadingSettings.putVolumePage(volumePage)
        ReadingSettings.putReadingFullscreen(readingFullscreen)
        ReadingSettings.putCustomScreenLightness(customScreenLightness)
        ReadingSettings.putScreenLightness(screenLightness)
        ReadingSettings.putReverseVolumePage(reverseVolumePage)

        // Notify Activity to apply changes
        mCallback?.onSettingsApplied(
            screenRotation, layoutMode, scaleMode, startPosition,
            keepScreenOn, showClock, showProgress, showBattery, showPageInterval,
            volumePage, reverseVolumePage, readingFullscreen,
            customScreenLightness, screenLightness, transferTime
        )
    }
}
