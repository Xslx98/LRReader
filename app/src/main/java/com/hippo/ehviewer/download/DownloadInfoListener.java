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

import androidx.annotation.NonNull;

import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.LinkedList;
import java.util.List;

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
