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
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.unifile.UniFile

class CleanRedundancyPreference : TaskPreference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onCreateTask(): Task = ClearTask(context)

    private class ClearTask(context: Context) : Task(context) {

        private val mApplication: EhApplication = context.applicationContext as EhApplication
        private val mManager = ServiceRegistry.dataModule.downloadManager

        // True for cleared
        private fun clearFile(file: UniFile): Boolean {
            var name = file.name ?: return false
            val index = name.indexOf('-')
            if (index >= 0) {
                name = name.substring(0, index)
            }
            val gid = NumberUtils.parseLongSafely(name, -1L)
            if (gid == -1L) {
                return false
            }
            if (mManager.containDownloadInfo(gid)) {
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
