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

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.lib.glgallery.GalleryView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Handles keyboard, volume key, mouse scroll, and auto-read input
 * for the gallery reader. Extracted from GalleryActivity to reduce
 * its responsibility scope.
 */
class GalleryInputHandler(private val mCallback: Callback) {

    /** Callback interface for events that need Activity-level handling. */
    interface Callback {
        fun onTapMenuArea()
    }

    var galleryView: GalleryView? = null
    var autoTransferPanel: ImageView? = null
    var layoutMode: Int = 0
    var isAutoTransferring: Boolean = false
        private set

    private var mTransferService: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor()
    private val mTransHandle = Handler(Looper.getMainLooper())

    /**
     * Handle key-down events for volume keys, D-pad, and keyboard.
     * @return true if the event was consumed
     */
    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val galleryView = galleryView ?: return false
        val unReverse = !ReadingSettings.getReverseVolumePage()
        // Volume keys
        if (ReadingSettings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    galleryView.pageRight()
                } else {
                    galleryView.pageLeft()
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    galleryView.pageLeft()
                } else {
                    galleryView.pageRight()
                }
                return true
            }
        }

        // Keyboard and D-pad
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    galleryView.pageRight()
                } else {
                    galleryView.pageLeft()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                galleryView.pageLeft()
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    galleryView.pageLeft()
                } else {
                    galleryView.pageRight()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                galleryView.pageRight()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MENU -> {
                mCallback.onTapMenuArea()
                return true
            }
        }

        return false
    }

    /**
     * Handle key-up events -- consume keys that were handled in key-down.
     * @return true if the event was consumed
     */
    fun handleKeyUp(keyCode: Int): Boolean {
        if (ReadingSettings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP
            || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE
            || keyCode == KeyEvent.KEYCODE_MENU
        ) {
            return true
        }
        return false
    }

    /**
     * Handle mouse scroll / trackpad input for page navigation.
     * @return true if the event was consumed
     */
    fun handleGenericMotion(view: View?, motionEvent: MotionEvent): Boolean {
        val galleryView = galleryView ?: return false
        if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (motionEvent.action == MotionEvent.ACTION_SCROLL) {
                val scrollY = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (scrollY == 0f) return false
                if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    if (scrollY > 0) {
                        galleryView.pageLeft()
                    } else {
                        galleryView.pageRight()
                    }
                } else {
                    if (scrollY < 0) {
                        galleryView.pageLeft()
                    } else {
                        galleryView.pageRight()
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * Toggle auto-read (auto page-turn) on/off.
     */
    fun toggleAutoRead(view: View?) {
        isAutoTransferring = !isAutoTransferring
        val panel = autoTransferPanel ?: return

        if (!isAutoTransferring) {
            panel.setImageResource(R.drawable.ic_start_play_24)
            mTransferService?.shutdown()
        } else {
            panel.setImageResource(R.drawable.ic_pause_circle)
            if (mTransferService?.isShutdown == true) {
                mTransferService = Executors.newSingleThreadScheduledExecutor()
            }
            val initialDelay = ReadingSettings.getStartTransferTime().toLong()
            val waitTime = initialDelay * 2L
            try {
                mTransferService?.scheduleWithFixedDelay({
                    mTransHandle.post {
                        val gv = galleryView ?: return@post
                        if (layoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                            gv.pageLeft()
                        } else {
                            gv.pageRight()
                        }
                    }
                }, initialDelay, waitTime, TimeUnit.SECONDS)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Schedule auto-read timer", e)
            }
        }
    }

    /** Called when auto-transfer reaches the last page. */
    fun onAutoTransferDone() {
        if (isAutoTransferring) {
            toggleAutoRead(autoTransferPanel)
        }
    }

    /** Shut down the auto-read executor. Call from Activity.onDestroy(). */
    fun shutdown() {
        mTransferService?.let {
            if (!it.isShutdown) {
                it.shutdown()
            }
        }
        mTransferService = null
    }

    companion object {
        private const val TAG = "GalleryInputHandler"
    }
}
