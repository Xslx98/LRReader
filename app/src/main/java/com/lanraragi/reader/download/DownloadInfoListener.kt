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

package com.lanraragi.reader.download

import com.lanraragi.reader.dao.DownloadInfo

interface DownloadInfoListener {

    /**
     * [newInfo] took the place of [oldInfo] (same arcid, another row).
     */
    fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo)

    /**
     * The list is gone, use default list please
     */
    fun onChange()

    /**
     * Rename label
     */
    fun onRenameLabel(from: String, to: String)

    /**
     * The label list changed (added, removed or reordered).
     */
    fun onUpdateLabels()
}
