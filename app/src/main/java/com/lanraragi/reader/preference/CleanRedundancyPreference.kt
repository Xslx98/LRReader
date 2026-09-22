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

package com.lanraragi.reader.preference

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.download.RedundantDownloadScanner
import com.lanraragi.reader.settings.DownloadSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Clear download redundancy": scans the download location for
 * directories that belong to no download ([RedundantDownloadScanner]),
 * then asks before deleting — listing how many and which — so a scan bug
 * can never silently delete downloads again.
 */
class CleanRedundancyPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var scanning = false

    override fun onClick() {
        if (scanning) return
        scanning = true
        val appContext = context.applicationContext
        val scope = ServiceRegistry.coroutineModule.ioScope
        scope.launch {
            val candidates = try {
                val root = DownloadSettings.getDownloadLocation()
                if (root == null) emptyList() else RedundantDownloadScanner(
                    ServiceRegistry.dataModule.downloadDbRepository
                ).scan(root)
            } catch (e: Exception) {
                Log.e(TAG, "Redundancy scan failed", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                scanning = false
                if (candidates.isEmpty()) {
                    Toast.makeText(
                        appContext, R.string.settings_download_clean_redundancy_no_redundancy, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    confirmDelete(candidates)
                }
            }
        }
    }

    private fun confirmDelete(candidates: List<UniFile>) {
        val names = candidates.take(PREVIEW_COUNT).joinToString("\n") { "• ${it.name}" } +
            if (candidates.size > PREVIEW_COUNT) "\n…" else ""
        AlertDialog.Builder(context)
            .setTitle(
                context.resources.getQuantityString(
                    R.plurals.settings_download_clean_redundancy_confirm, candidates.size, candidates.size
                )
            )
            .setMessage(names)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.batch_delete) { _, _ -> delete(candidates) }
            .show()
    }

    private fun delete(candidates: List<UniFile>) {
        val appContext = context.applicationContext
        ServiceRegistry.coroutineModule.ioScope.launch {
            val count = candidates.count { dir ->
                try {
                    dir.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete a redundant download directory", e)
                    false
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.settings_download_clean_redundancy_done, count),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private companion object {
        const val TAG = "CleanRedundancy"
        const val PREVIEW_COUNT = 8
    }
}
