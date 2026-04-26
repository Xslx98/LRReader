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

package com.hippo.ehviewer.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.unifile.UniFile

class CleanRedundancyPreference : TaskPreference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onCreateTask(): Task = ClearTask(context)

    private class ClearTask(context: Context) : Task(context) {

        private val mApplication: EhApplication = context.applicationContext as EhApplication
        private val mManager = ServiceRegistry.dataModule.downloadManager

        // Snapshot of arcid prefixes for all known downloads. Files in the
        // download dir are named "<arcid>-<title>" (post-v20 format) or with
        // a custom dirname stored in DOWNLOAD_DIRNAME (resolved by SpiderDen).
        // Match by arcid prefix here — covers the common case; legacy
        // gid-prefixed dirs from pre-v20 installs that have not been opened
        // since upgrade may be wrongly flagged as orphan, but those dirs
        // are repaired the next time the user opens the corresponding
        // download (SpiderDen.getGalleryDownloadDir falls back to gid prefix
        // and writes the canonical dirname into DOWNLOAD_DIRNAME).
        private val knownArcids: Set<String> =
            mManager.allDownloadInfoList.mapTo(HashSet()) { it.arcid }

        // True for cleared
        private fun clearFile(file: UniFile): Boolean {
            val name = file.name ?: return false
            val dashIdx = name.indexOf('-')
            val prefix = if (dashIdx >= 0) name.substring(0, dashIdx) else name
            if (prefix.isEmpty()) {
                return false
            }
            if (prefix in knownArcids) {
                return false
            }
            file.delete()
            return true
        }

        override fun doWork(): Any? {
            val dir = DownloadSettings.getDownloadLocation() ?: run {
                publishProgress(0, 0)
                return 0
            }
            val files = dir.listFiles() ?: run {
                publishProgress(0, 0)
                return 0
            }

            val total = files.size
            var count = 0
            for (i in 0 until total) {
                if (clearFile(files[i])) {
                    count++
                }
                publishProgress(i + 1, total)
            }

            return count
        }

        override fun onPostExecute(result: Any?) {
            val count = (result as? Int) ?: 0

            Toast.makeText(
                mApplication,
                if (count == 0) {
                    mApplication.getString(R.string.settings_download_clean_redundancy_no_redundancy)
                } else {
                    mApplication.getString(R.string.settings_download_clean_redundancy_done, count)
                },
                Toast.LENGTH_SHORT
            ).show()
            super.onPostExecute(result)
        }
    }
}
