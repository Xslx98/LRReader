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

package com.hippo.ehviewer.download;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.SparseIJArray;

import java.util.LinkedList;
import java.util.List;

/**
 * Calculates and reports download speed and remaining time on a 2-second interval.
 * Extracted from DownloadManager to give it a single responsibility.
 *
 * Callbacks are supplied via {@link Callback} so this class does not need a
 * direct reference to DownloadManager.
 */
class DownloadSpeedTracker implements Runnable {

    /** Supplies the data DownloadSpeedTracker needs from DownloadManager. */
    interface Callback {
        /** @return the first currently-active task, or null if idle. */
        @Nullable DownloadInfo getFirstActiveTask();

        /** @return the per-label list used for listener notifications. */
        @Nullable List<DownloadInfo> getInfoListForLabel(@Nullable String label);

        /** @return the active DownloadListener (may be null). */
        @Nullable DownloadListener getDownloadListener();

        /** @return all registered DownloadInfoListeners. */
        List<DownloadInfoListener> getDownloadInfoListeners();

        /** @return the current wait list (passed to onUpdate). */
        LinkedList<DownloadInfo> getWaitList();
    }

    private final Callback mCallback;

    private boolean mStop = true;

    private long mBytesRead;
    private long oldSpeed = -1;

    private final SparseIJArray mContentLengthMap = new SparseIJArray();
    private final SparseIJArray mReceivedSizeMap = new SparseIJArray();

    DownloadSpeedTracker(Callback callback) {
        mCallback = callback;
    }

    public void start() {
        if (mStop) {
            mStop = false;
            SimpleHandler.getInstance().post(this);
        }
    }

    public void stop() {
        if (!mStop) {
            mStop = true;
            mBytesRead = 0;
            oldSpeed = -1;
            mContentLengthMap.clear();
            mReceivedSizeMap.clear();
            SimpleHandler.getInstance().removeCallbacks(this);
        }
    }

    public void onDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        mContentLengthMap.put(index, contentLength);
        mReceivedSizeMap.put(index, receivedSize);
        mBytesRead += bytesRead;
    }

    public void onDone(int index) {
        mContentLengthMap.delete(index);
        mReceivedSizeMap.delete(index);
    }

    public void onFinish() {
        mContentLengthMap.clear();
        mReceivedSizeMap.clear();
    }

    @Override
    public void run() {
        DownloadInfo info = mCallback.getFirstActiveTask();
        if (info != null) {
            long newSpeed = mBytesRead / 2;
            if (oldSpeed != -1) {
                newSpeed = (long) MathUtils.lerp(oldSpeed, newSpeed, 0.75f);
            }
            oldSpeed = newSpeed;
            info.speed = newSpeed;

            // Calculate remaining time
            if (info.total <= 0) {
                info.remaining = -1;
            } else if (newSpeed == 0) {
                info.remaining = 300L * 24L * 60L * 60L * 1000L; // 300 days
            } else {
                int downloadingCount = 0;
                long downloadingContentLengthSum = 0;
                long totalSize = 0;
                for (int i = 0, n = Math.max(mContentLengthMap.size(), mReceivedSizeMap.size()); i < n; i++) {
                    long contentLength = mContentLengthMap.valueAt(i);
                    long receivedSize = mReceivedSizeMap.valueAt(i);
                    downloadingCount++;
                    downloadingContentLengthSum += contentLength;
                    totalSize += contentLength - receivedSize;
                }
                if (downloadingCount != 0) {
                    totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount;
                    info.remaining = totalSize / newSpeed * 1000;
                }
            }

            DownloadListener listener = mCallback.getDownloadListener();
            if (listener != null) {
                listener.onDownload(info);
            }
            List<DownloadInfo> list = mCallback.getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mCallback.getDownloadInfoListeners()) {
                    l.onUpdate(info, list, mCallback.getWaitList());
                }
            }
        }

        mBytesRead = 0;

        if (!mStop) {
            SimpleHandler.getInstance().postDelayed(this, 2000);
        }
    }
}
