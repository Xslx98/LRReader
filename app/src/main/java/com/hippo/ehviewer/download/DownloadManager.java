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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.DownloadSettings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;

import com.hippo.lib.yorozuya.collect.SparseJLArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DownloadManager {

    private static final String TAG = DownloadManager.class.getSimpleName();

    public static final String DOWNLOAD_INFO_FILENAME = ".ehviewer";
    public static final String DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages";

    private final Context mContext;

    // All download info list
    private final LinkedList<DownloadInfo> mAllInfoList;
    // All download info map
    private final SparseJLArray<DownloadInfo> mAllInfoMap;
    // label and info list map, without default label info list
    private final Map<String, LinkedList<DownloadInfo>> mMap;

    private final Map<String, Long> mLabelCountMap;
    // All labels without default label
    private final List<DownloadLabel> mLabelList;
    // Store download info with default label
    private final LinkedList<DownloadInfo> mDefaultInfoList;
    // Store download info wait to start
    private final LinkedList<DownloadInfo> mWaitList;

    private final DownloadSpeedTracker mSpeedReminder;

    @Nullable
    private DownloadListener mDownloadListener;
    private final List<DownloadInfoListener> mDownloadInfoListeners;

    private final List<DownloadInfo> mActiveTasks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<DownloadInfo, LRRDownloadWorker> mActiveWorkers = new java.util.concurrent.ConcurrentHashMap<>();

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);

    public DownloadManager(Context context) {
        mContext = context;

        // Get all labels
        List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        mLabelList = labels;

        // Create list for each label
        HashMap<String, LinkedList<DownloadInfo>> map = new HashMap<>();
        mMap = map;
        for (DownloadLabel label : labels) {
            map.put(label.getLabel(), new LinkedList<>());
        }

        // Create default for non tag
        mDefaultInfoList = new LinkedList<>();

        // Get all info
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList = new LinkedList<>(allInfoList);

        // Create all info map
        SparseJLArray<DownloadInfo> allInfoMap = new SparseJLArray<>(allInfoList.size() + 10);
        mAllInfoMap = allInfoMap;

        for (int i = 0, n = allInfoList.size(); i < n; i++) {
            DownloadInfo info = allInfoList.get(i);

            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(info.archiveUri);
                    mContext.getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    // Permission might already be taken or URI might be invalid
                    android.util.Log.w("DownloadManager", "Failed to restore URI permission for " + info.archiveUri, e);
                }
            }

            // Add to all info map
            allInfoMap.put(info.gid, info);

            // Add to each label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                // Can't find the label in label list
                list = new LinkedList<>();
                map.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
        }

        mLabelCountMap = new HashMap<>();

        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : map.entrySet()) {
            mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
        }

        mWaitList = new LinkedList<>();
        mDownloadInfoListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        mSpeedReminder = new DownloadSpeedTracker(new DownloadSpeedTracker.Callback() {
            @Override
            public DownloadInfo getFirstActiveTask() {
                return mActiveTasks.isEmpty() ? null : mActiveTasks.get(0);
            }
            @Override
            public List<DownloadInfo> getInfoListForLabel(String label) {
                return DownloadManager.this.getInfoListForLabel(label);
            }
            @Override
            public DownloadListener getDownloadListener() {
                return mDownloadListener;
            }
            @Override
            public List<DownloadInfoListener> getDownloadInfoListeners() {
                return mDownloadInfoListeners;
            }
            @Override
            public LinkedList<DownloadInfo> getWaitList() {
                return mWaitList;
            }
        });
    }

    public void replaceInfo(DownloadInfo newInfo, DownloadInfo oldInfo) {

        for (int i = 0; i < mAllInfoList.size(); i++) {
            if (oldInfo.gid == mAllInfoList.get(i).gid) {
                mAllInfoList.set(i, newInfo);
                break;
            }
        }
        final List<DownloadInfo> infoList = getInfoListForLabel(oldInfo.label);
        if (infoList != null) {
            for (int i = 0; i < infoList.size(); i++) {
                if (oldInfo.gid == infoList.get(i).gid) {
                    infoList.set(i, newInfo);
                    break;
                }
            }
        }

        mAllInfoMap.remove(oldInfo.gid);
        mAllInfoMap.put(newInfo.gid, newInfo);


        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReplace(newInfo, oldInfo);
        }
    }

    @Nullable
    private LinkedList<DownloadInfo> getInfoListForLabel(String label) {
        if (label == null) {
            return mDefaultInfoList;
        } else {
            return mMap.get(label);
        }
    }

    public boolean containLabel(String label) {
        if (label == null) {
            return false;
        }

        for (DownloadLabel raw : mLabelList) {
            if (label.equals(raw.getLabel())) {
                return true;
            }
        }

        return false;
    }

    public boolean containDownloadInfo(long gid) {
        return mAllInfoMap.indexOfKey(gid) >= 0;
    }

    @NonNull
    public List<DownloadLabel> getLabelList() {
        return mLabelList;
    }

    @Nullable
    public long getLabelCount(String label) {
        try {
            if (mLabelCountMap.containsKey(label)) {
                return mLabelCountMap.get(label);
            } else {
                return 0;
            }
        } catch (NullPointerException e) {
            Analytics.recordException(e);
            return 0;
        }
    }

    public List<DownloadInfo> getAllDownloadInfoList() {
        return mAllInfoList;
    }

    /**
     * Reload download data from DB for the current server profile.
     * Call this after switching servers.
     */
    public void reload() {
        // Stop any current downloads
        stopAllDownload();

        // Clear in-memory lists
        mAllInfoList.clear();
        mAllInfoMap.clear();
        mDefaultInfoList.clear();
        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : mMap.entrySet()) {
            entry.getValue().clear();
        }

        // Reload from DB (filtered by current profile)
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList.addAll(allInfoList);
        for (DownloadInfo info : allInfoList) {
            mAllInfoMap.put(info.gid, info);
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                list = new LinkedList<>();
                mMap.put(info.label, list);
            }
            list.add(info);
        }

        // Notify listeners
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }
    }

    @NonNull
    public List<DownloadInfo> getDefaultDownloadInfoList() {
        return mDefaultInfoList;
//        List<DownloadInfo> infoList = new ArrayList<>();
//        int i = 0;
//        while (infoList.size() < 30000) {
//            if (i == mDefaultInfoList.size()) {
//                i = 0;
//            }
//            infoList.add(mDefaultInfoList.get(i));
//            i++;
//        }
//        return infoList;
    }

    @Nullable
    public List<DownloadInfo> getLabelDownloadInfoList(String label) {
        return mMap.get(label);
    }

    public List<GalleryInfo> getDownloadInfoList() {
        return new ArrayList<>(mAllInfoList);
    }

    @Nullable
    public DownloadInfo getDownloadInfo(long gid) {
        return mAllInfoMap.get(gid);
    }

    @Nullable
    public DownloadInfo getNoneDownloadInfo(long gid) {
        boolean wasActive = false;
        for (DownloadInfo info : mActiveTasks) {
            if (info.gid == gid) {
                wasActive = true;
                break;
            }
        }
        if (wasActive) {
            stopDownloadInternal(gid);
        } else {
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE;
                    iterator.remove();
                    break;
                }
            }
        }
        return mAllInfoMap.get(gid);
    }

    public int getDownloadState(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (null != info) {
            return info.state;
        } else {
            return DownloadInfo.STATE_INVALID;
        }
    }

    public void addDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.add(downloadInfoListener);
    }

    public void removeDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.remove(downloadInfoListener);
    }

    public void setDownloadListener(@Nullable DownloadListener listener) {
        mDownloadListener = listener;
    }

    private void ensureDownload() {
        int maxConcurrent = DownloadSettings.getConcurrentDownloads();
        while (mActiveTasks.size() < maxConcurrent && !mWaitList.isEmpty()) {
            DownloadInfo info = mWaitList.removeFirst();
            LRRDownloadWorker worker = new LRRDownloadWorker(mContext, info);
            mActiveTasks.add(info);
            mActiveWorkers.put(info, worker);
            worker.setListener(new PerTaskListener(info));
            info.state = DownloadInfo.STATE_DOWNLOAD;
            info.speed = -1;
            info.remaining = -1;
            info.total = -1;
            info.finished = 0;
            info.downloaded = 0;
            info.legacy = -1;
            // Update in DB
            EhDB.putDownloadInfo(info);
            // Start speed count
            mSpeedReminder.start();
            // Notify start downloading
            if (mDownloadListener != null) {
                mDownloadListener.onStart(info);
            }
            // Notify state update
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Start the worker
            worker.start();
        }
    }

    void startDownload(GalleryInfo galleryInfo, @Nullable String label) {
        for (DownloadInfo active : mActiveTasks) {
            if (active.gid == galleryInfo.gid) return; // already downloading
        }

        // Do nothing in the case of a local compressed file.
        if (galleryInfo instanceof DownloadInfo downloadInfo) {
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")){
                return;
            }
        }

        // Check in download list
        DownloadInfo info = mAllInfoMap.get(galleryInfo.gid);

        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT;
                // Add to wait list
                mWaitList.add(info);
                // Update in DB
                EhDB.putDownloadInfo(info);
                // Notify state update
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
                // Make sure download is running
                ensureDownload();
            }
        } else {
            // It is new download info
            info = new DownloadInfo(galleryInfo);
            info.label = label;
            info.state = DownloadInfo.STATE_WAIT;
            info.time = System.currentTimeMillis();

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: " + label);
                return;
            }
            list.addFirst(info);

            // Add to all download list and map
            mAllInfoList.addFirst(info);
            mAllInfoMap.put(galleryInfo.gid, info);

            // Add to wait list
            mWaitList.add(info);

            // Save to
            EhDB.putDownloadInfo(info);

            // Notify
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onAdd(info, list, list.size() - 1);
            }
            // Make sure download is running
            ensureDownload();

            // Add it to history
            EhDB.putHistoryInfo(info);
        }
    }

    void startRangeDownload(LongList gidList) {
        boolean update = false;
        boolean downloadOrder = DownloadSettings.getDownloadOrder();
        if (downloadOrder) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                long gid = gidList.get(i);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (int i = gidList.size(), n = 0; i > n; i--) {
                long gid = gidList.get(i - 1);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    void startAllDownload() {
        boolean update = false;
        // Start all STATE_NONE and STATE_FAILED item
        LinkedList<DownloadInfo> allInfoList = mAllInfoList;
        LinkedList<DownloadInfo> waitList = mWaitList;
        boolean downloadOrder = DownloadSettings.getDownloadOrder();
        if (downloadOrder) {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.addFirst(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void addDownload(List<DownloadInfo> downloadInfoList) {
        for (DownloadInfo info : downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                // Contain
                continue;
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                    DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE;
            }

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (null == list) {
                // Can't find the label in label list
                list = new LinkedList<>();
                mMap.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
            // Sort
            Collections.sort(list, DATE_DESC_COMPARATOR);

            // Add to all download list and map
            mAllInfoList.add(info);
            mAllInfoMap.put(info.gid, info);

            // Save to
            EhDB.putDownloadInfo(info);
        }

        // Sort all download list
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR);

        // Notify
        new Handler(Looper.getMainLooper()).post(() -> {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onReload();
            }
        });
    }

    public void addDownloadLabel(List<DownloadLabel> downloadLabelList) {
        for (DownloadLabel label : downloadLabelList) {
            String labelString = label.getLabel();
            if (!containLabel(labelString)) {
                mMap.put(labelString, new LinkedList<>());
                mLabelList.add(EhDB.addDownloadLabel(label));
            }
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label, int state) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = state;
        info.time = System.currentTimeMillis();

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (!mLabelCountMap.containsKey(label)) {
            mLabelCountMap.put(label, 1L);
        } else {
            long value = mLabelCountMap.get(label) + 1L;
            mLabelCountMap.put(label, value);
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Add to all download list and map
        mAllInfoList.addFirst(info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // Save to
        EhDB.putDownloadInfo(info);

        // Notify
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onAdd(info, list, list.size() - 1);
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE);
    }

    public void addDownloadInfo(GalleryInfo galleryInfo, @Nullable String label) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = DownloadInfo.STATE_NONE;
        if (info.time == 0) {
            info.time = System.currentTimeMillis();
        }

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Save to
        EhDB.putDownloadInfo(info);
        mAllInfoMap.put(galleryInfo.gid, info);
    }


    public void stopDownload(long gid) {
        DownloadInfo info = stopDownloadInternal(gid);
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    void stopCurrentDownload() {
        DownloadInfo info = stopCurrentDownloadInternal();
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void stopRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }

        // Ensure download
        ensureDownload();
    }

    public void stopAllDownload() {
        // Stop all in wait list
        for (DownloadInfo info : mWaitList) {
            info.state = DownloadInfo.STATE_NONE;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        mWaitList.clear();

        // Stop current
        stopCurrentDownloadInternal();

        // Notify mDownloadInfoListener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    public void deleteDownload(long gid) {
        stopDownloadInternal(gid);
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove all list and map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                int index = list.indexOf(info);
                if (index >= 0) {
                    list.remove(info);
                    // Update listener
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onRemove(info, list, index);
                    }
                }
            }

            // Ensure download
            ensureDownload();
        }
    }

    public void deleteRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: " + gid);
                continue;
            }

            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove from all info map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove from label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                list.remove(info);
            }
        }

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }

        // Ensure download
        ensureDownload();
    }

    public void resetAllReadingProgress() {
        LinkedList<DownloadInfo> list = new LinkedList<>(mAllInfoList);

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            GalleryInfo galleryInfo = new GalleryInfo();
            for (DownloadInfo downloadInfo : list) {
                galleryInfo.gid = downloadInfo.gid;
                galleryInfo.token = downloadInfo.token;
                galleryInfo.title = downloadInfo.title;
                galleryInfo.thumb = downloadInfo.thumb;
                galleryInfo.category = downloadInfo.category;
                galleryInfo.posted = downloadInfo.posted;
                galleryInfo.uploader = downloadInfo.uploader;
                galleryInfo.rating = downloadInfo.rating;

                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo);
                if (downloadDir == null) {
                    continue;
                }
                UniFile file = downloadDir.findFile(".ehviewer");
                if (file == null) {
                    continue;
                }
                SpiderInfo spiderInfo = SpiderInfo.read(file);
                if (spiderInfo == null) {
                    continue;
                }
                spiderInfo.startPage = 0;

                try {
                    spiderInfo.write(file.openOutputStream());
                } catch (IOException e) {
                    Log.e(TAG, "Can't write SpiderInfo", e);
                }
            }
        });
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private DownloadInfo stopDownloadInternal(long gid) {
        // Check active tasks
        for (Iterator<DownloadInfo> it = mActiveTasks.iterator(); it.hasNext(); ) {
            DownloadInfo info = it.next();
            if (info.gid == gid) {
                LRRDownloadWorker w = mActiveWorkers.remove(info);
                if (w != null) w.cancel();
                it.remove();
                if (mActiveTasks.isEmpty()) mSpeedReminder.stop();
                info.state = DownloadInfo.STATE_NONE;
                EhDB.putDownloadInfo(info);
                if (mDownloadListener != null) mDownloadListener.onCancel(info);
                return info;
            }
        }

        for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
            DownloadInfo info = iterator.next();
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove();
                // Update state
                info.state = DownloadInfo.STATE_NONE;
                // Update in DB
                EhDB.putDownloadInfo(info);
                return info;
            }
        }
        return null;
    }

    // Update in DB
    // Update mDownloadListener
    private DownloadInfo stopCurrentDownloadInternal() {
        // Cancel all active workers
        for (LRRDownloadWorker w : mActiveWorkers.values()) {
            w.cancel();
        }
        List<DownloadInfo> stopped = new ArrayList<>(mActiveTasks);
        mActiveTasks.clear();
        mActiveWorkers.clear();
        mSpeedReminder.stop();
        if (stopped.isEmpty()) return null;
        for (DownloadInfo info : stopped) {
            info.state = DownloadInfo.STATE_NONE;
            EhDB.putDownloadInfo(info);
            if (mDownloadListener != null) mDownloadListener.onCancel(info);
        }
        return stopped.get(0);
    }

    // Update in DB
    // Update mDownloadListener
    private void stopRangeDownloadInternal(LongList gidList) {
        // Two way
        if (gidList.size() < mWaitList.size()) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                stopDownloadInternal(gidList.get(i));
            }
        } else {
            // Check active tasks
            for (DownloadInfo active : new ArrayList<>(mActiveTasks)) {
                if (gidList.contains(active.gid)) {
                    stopDownloadInternal(active.gid);
                }
            }

            // Check all in wait list
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove();
                    // Update state
                    info.state = DownloadInfo.STATE_NONE;
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    public void changeLabel(List<DownloadInfo> list, String label) {
        if (null != label && !containLabel(label)) {
            Log.e(TAG, "Not exits label: " + label);
            return;
        }

        List<DownloadInfo> dstList = getInfoListForLabel(label);
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: " + label);
            return;
        }

        for (DownloadInfo info : list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue;
            }

            List<DownloadInfo> srcList = getInfoListForLabel(info.label);
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label);
                continue;
            }

            srcList.remove(info);
            dstList.add(info);
            info.label = label;
            Collections.sort(dstList, DATE_DESC_COMPARATOR);

            // Save to DB
            EhDB.putDownloadInfo(info);
        }

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }
    }

    public void addLabel(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void addLabelInSyncThread(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());
    }

    public void moveLabel(int fromPosition, int toPosition) {
        final DownloadLabel item = mLabelList.remove(fromPosition);
        mLabelList.add(toPosition, item);
        EhDB.moveDownloadLabel(fromPosition, toPosition);

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void renameLabel(@NonNull String from, @NonNull String to) {
        // Find in label list
        boolean found = false;
        for (DownloadLabel raw : mLabelList) {
            if (from.equals(raw.getLabel())) {
                found = true;
                raw.setLabel(to);
                // Update in DB
                EhDB.updateDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(from);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = to;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        // Put list back with new label
        mMap.put(to, list);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onRenameLabel(from, to);
        }
    }

    public void deleteLabel(@NonNull String label) {
        // Find in label list and remove
        boolean found = false;
        for (Iterator<DownloadLabel> iterator = mLabelList.iterator(); iterator.hasNext(); ) {
            DownloadLabel raw = iterator.next();
            if (label.equals(raw.getLabel())) {
                found = true;
                iterator.remove();
                EhDB.removeDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(label);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = null;
            // Update in DB
            EhDB.putDownloadInfo(info);
            mDefaultInfoList.add(info);
        }

        // Sort
        Collections.sort(mDefaultInfoList, DATE_DESC_COMPARATOR);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onChange();
        }
    }

    boolean isIdle() {
        return mActiveTasks.isEmpty() && mWaitList.isEmpty();
    }

    private class NotifyTask implements Runnable {

        public static final int TYPE_ON_GET_PAGES = 0;
        public static final int TYPE_ON_GET_509 = 1;
        public static final int TYPE_ON_PAGE_DOWNLOAD = 2;
        public static final int TYPE_ON_PAGE_SUCCESS = 3;
        public static final int TYPE_ON_PAGE_FAILURE = 4;
        public static final int TYPE_ON_FINISH = 5;

        private int mType;
        private DownloadInfo mTaskInfo; // task identity for task-specific events
        private int mPages;
        private int mIndex;
        private long mContentLength;
        private long mReceivedSize;
        private int mBytesRead;
        @SuppressWarnings("unused")
        private String mError;
        private int mFinished;
        private int mDownloaded;
        private int mTotal;

        public void setOnGetPagesData(DownloadInfo taskInfo, int pages) {
            mType = TYPE_ON_GET_PAGES;
            mTaskInfo = taskInfo;
            mPages = pages;
        }

        public void setOnGet509Data(int index) {
            mType = TYPE_ON_GET_509;
            mIndex = index;
        }

        public void setOnPageDownloadData(int index, long contentLength, long receivedSize, int bytesRead) {
            mType = TYPE_ON_PAGE_DOWNLOAD;
            mIndex = index;
            mContentLength = contentLength;
            mReceivedSize = receivedSize;
            mBytesRead = bytesRead;
        }

        public void setOnPageSuccessData(DownloadInfo taskInfo, int index, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_SUCCESS;
            mTaskInfo = taskInfo;
            mIndex = index;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnPageFailureDate(DownloadInfo taskInfo, int index, String error, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_FAILURE;
            mTaskInfo = taskInfo;
            mIndex = index;
            mError = error;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnFinishDate(DownloadInfo taskInfo, int finished, int downloaded, int total) {
            mType = TYPE_ON_FINISH;
            mTaskInfo = taskInfo;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        @Override
        public void run() {
            switch (mType) {
                case TYPE_ON_GET_PAGES: {
                    DownloadInfo info = mTaskInfo;
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onGetPages");
                    } else {
                        info.total = mPages;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_GET_509: {
                    if (mDownloadListener != null) {
                        mDownloadListener.onGet509();
                    }
                    break;
                }
                case TYPE_ON_PAGE_DOWNLOAD: {
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead);
                    break;
                }
                case TYPE_ON_PAGE_SUCCESS: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mTaskInfo;
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onPageSuccess");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        if (mDownloadListener != null) {
                            mDownloadListener.onGetPage(info);
                        }
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_PAGE_FAILURE: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mTaskInfo;
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onPageFailure");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_FINISH: {
                    mSpeedReminder.onFinish();
                    DownloadInfo info = mTaskInfo;
                    if (info == null) {
                        Log.e(TAG, "Task info is null on onFinish");
                        break;
                    }
                    mActiveTasks.remove(info);
                    mActiveWorkers.remove(info);
                    if (mActiveTasks.isEmpty()) mSpeedReminder.stop();
                    // Update state
                    info.finished = mFinished;
                    info.downloaded = mDownloaded;
                    info.total = mTotal;
                    info.legacy = mTotal - mFinished;
                    if (info.legacy == 0) {
                        info.state = DownloadInfo.STATE_FINISH;
                    } else {
                        info.state = DownloadInfo.STATE_FAILED;
                    }
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                    // Notify
                    if (mDownloadListener != null) {
                        mDownloadListener.onFinish(info);
                    }
                    List<DownloadInfo> list = getInfoListForLabel(info.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(info, list, mWaitList);
                        }
                    }
                    // Start next download
                    ensureDownload();
                    break;
                }
            }

            mNotifyTaskPool.push(this);
        }
    }

    private class PerTaskListener implements SpiderQueen.OnSpiderListener {
        private final DownloadInfo mInfo;

        PerTaskListener(DownloadInfo info) {
            mInfo = info;
        }

        private NotifyTask popTask() {
            NotifyTask task = mNotifyTaskPool.pop();
            return task != null ? task : new NotifyTask();
        }

        @Override
        public void onGetPages(int pages) {
            NotifyTask task = popTask();
            task.setOnGetPagesData(mInfo, pages);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onGet509(int index) {
            NotifyTask task = popTask();
            task.setOnGet509Data(index);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            NotifyTask task = popTask();
            task.setOnPageDownloadData(index, contentLength, receivedSize, bytesRead);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onPageSuccess(int index, int finished, int downloaded, int total) {
            NotifyTask task = popTask();
            task.setOnPageSuccessData(mInfo, index, finished, downloaded, total);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
            NotifyTask task = popTask();
            task.setOnPageFailureDate(mInfo, index, error, finished, downloaded, total);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onFinish(int finished, int downloaded, int total) {
            NotifyTask task = popTask();
            task.setOnFinishDate(mInfo, finished, downloaded, total);
            SimpleHandler.getInstance().post(task);
        }

        @Override
        public void onGetImageSuccess(int index, Image image) {}

        @Override
        public void onGetImageFailure(int index, String error) {}
    }


    private static final Comparator<DownloadInfo> DATE_DESC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            long dif = lhs.time - rhs.time;
            if (dif > 0) {
                return -1;
            } else if (dif < 0) {
                return 1;
            } else {
                return 0;
            }
//            return  > 0 ? -1 : 1;
        }
    };

    public interface DownloadInfoListener {

        /**
         * Add the special info to the special position
         */
        void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        /**
         * delete Old replace new
         */
        void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo);

        /**
         * The special info is changed
         */
        void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList);

        /**
         * Maybe all data is changed, but size is the same
         */
        void onUpdateAll();

        /**
         * Maybe all data is changed, maybe list is changed
         */
        void onReload();

        /**
         * The list is gone, use default list please
         */
        void onChange();

        /**
         * Rename label
         */
        void onRenameLabel(String from, String to);

        /**
         * Remove the special info from the special position
         */
        void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        void onUpdateLabels();
    }

    public interface DownloadListener {

        /**
         * Get 509 error
         */
        void onGet509();

        /**
         * Start download
         */
        void onStart(DownloadInfo info);

        /**
         * Update download speed
         */
        void onDownload(DownloadInfo info);

        /**
         * Update page downloaded
         */
        void onGetPage(DownloadInfo info);

        /**
         * Download done
         */
        void onFinish(DownloadInfo info);

        /**
         * Download done
         */
        void onCancel(DownloadInfo info);
    }

}
