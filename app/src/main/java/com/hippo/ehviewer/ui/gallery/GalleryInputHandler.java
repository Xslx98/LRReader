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

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.settings.ReadingSettings;
import com.hippo.lib.glgallery.GalleryView;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles keyboard, volume key, mouse scroll, and auto-read input
 * for the gallery reader. Extracted from GalleryActivity to reduce
 * its responsibility scope.
 */
public class GalleryInputHandler {

    /** Callback interface for events that need Activity-level handling. */
    public interface Callback {
        void onTapMenuArea();
    }

    @Nullable private GalleryView mGalleryView;
    @Nullable private ImageView mAutoTransferPanel;
    private int mLayoutMode;
    private boolean mAutoTransferring = false;

    private ScheduledExecutorService mTransferService = Executors.newSingleThreadScheduledExecutor();
    private final Handler mTransHandle = new Handler(Looper.getMainLooper());
    @NonNull private final Callback mCallback;

    public GalleryInputHandler(@NonNull Callback callback) {
        mCallback = callback;
    }

    public void setGalleryView(@Nullable GalleryView galleryView) {
        mGalleryView = galleryView;
    }

    public void setAutoTransferPanel(@Nullable ImageView panel) {
        mAutoTransferPanel = panel;
    }

    public void setLayoutMode(int layoutMode) {
        mLayoutMode = layoutMode;
    }

    public boolean isAutoTransferring() {
        return mAutoTransferring;
    }

    /**
     * Handle key-down events for volume keys, D-pad, and keyboard.
     * @return true if the event was consumed
     */
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (mGalleryView == null) {
            return false;
        }
        boolean unReverse = !ReadingSettings.getReverseVolumePage();
        // Volume keys
        if (ReadingSettings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            }
        }

        // Keyboard and D-pad
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mGalleryView.pageLeft();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mGalleryView.pageRight();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MENU:
                mCallback.onTapMenuArea();
                return true;
        }

        return false;
    }

    /**
     * Handle key-up events — consume keys that were handled in key-down.
     * @return true if the event was consumed
     */
    public boolean handleKeyUp(int keyCode) {
        if (ReadingSettings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE
                || keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return false;
    }

    /**
     * Handle mouse scroll / trackpad input for page navigation.
     * @return true if the event was consumed
     */
    public boolean handleGenericMotion(View view, MotionEvent motionEvent) {
        if (mGalleryView == null) {
            return false;
        }
        if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (motionEvent.getAction() == MotionEvent.ACTION_SCROLL) {
                float scrollY = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY == 0) return false;
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    if (scrollY > 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                } else {
                    if (scrollY < 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Toggle auto-read (auto page-turn) on/off.
     */
    public void toggleAutoRead(View view) {
        mAutoTransferring = !mAutoTransferring;
        if (mAutoTransferPanel == null) {
            return;
        }

        if (!mAutoTransferring) {
            mAutoTransferPanel.setImageResource(R.drawable.ic_start_play_24);
            mTransferService.shutdown();
        } else {
            mAutoTransferPanel.setImageResource(R.drawable.ic_pause_circle);
            if (mTransferService.isShutdown()) {
                mTransferService = Executors.newSingleThreadScheduledExecutor();
            }
            long initialDelay = ReadingSettings.getStartTransferTime();
            long waitTime = initialDelay * 2L;
            try {
                mTransferService.scheduleWithFixedDelay(() -> mTransHandle.post(() -> {
                    if (mGalleryView == null) {
                        return;
                    }
                    if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }), initialDelay, waitTime, TimeUnit.SECONDS);
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    /** Called when auto-transfer reaches the last page. */
    public void onAutoTransferDone() {
        if (mAutoTransferring) {
            toggleAutoRead(mAutoTransferPanel);
        }
    }

    /** Shut down the auto-read executor. Call from Activity.onDestroy(). */
    public void shutdown() {
        if (mTransferService != null && !mTransferService.isShutdown()) {
            mTransferService.shutdown();
        }
        mTransferService = null;
    }
}
