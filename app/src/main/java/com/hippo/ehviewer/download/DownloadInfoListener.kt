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

package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo

interface DownloadInfoListener {

    /**
     * Add the special info to the special position
     */
    fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int)

    /**
     * delete Old replace new
     */
    fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo)

    /**
     * The special info is changed
     */
    fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>)

    /**
     * Maybe all data is changed, but size is the same
     */
    fun onUpdateAll()

    /**
     * Maybe all data is changed, maybe list is changed
     */
    fun onReload()

    /**
     * The list is gone, use default list please
     */
    fun onChange()

    /**
     * Rename label
     */
    fun onRenameLabel(from: String, to: String)

    /**
     * Remove the special info from the special position
     */
    fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int)

    fun onUpdateLabels()
}
