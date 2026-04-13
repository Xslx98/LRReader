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
package com.hippo.ehviewer.ui.scene.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.easyrecyclerview.EasyRecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles gallery item click + read-process update after returning from
 * [GalleryActivity]. Extracted from [DownloadsScene] (W16-1).
 */
internal class DownloadGalleryOpenHelper(private val callback: Callback) {

    interface Callback {
        val ehContext: Context?
        val activity2: android.app.Activity?
        val viewModel: DownloadsViewModel
        val mList: List<DownloadInfo>?
        val mRecyclerView: EasyRecyclerView?
        val mAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?
        val viewLifecycleOwner: LifecycleOwner
        fun positionInList(position: Int): Int
        fun listIndexInPage(position: Int): Int
        fun launchGallery(intent: Intent)
    }

    /**
     * Handles a gallery item click. Returns true if the click was consumed.
     */
    fun onItemClick(position: Int): Boolean {
        val activity = callback.activity2 ?: return false
        val recyclerView = callback.mRecyclerView ?: return false
        val context = callback.ehContext ?: return false

        if (recyclerView.isInCustomChoice) {
            recyclerView.toggleItemChecked(position)
            return true
        }

        val list = callback.mList ?: return false
        if (position < 0 || position >= list.size) {
            return false
        }

        val downloadInfo = list[callback.positionInList(position)]
        // Check if this is an imported archive
        if (downloadInfo.archiveUri != null && downloadInfo.archiveUri!!.startsWith("content://")) {
            return openImportedArchive(activity, context, downloadInfo)
        }

        // Use GalleryOpenHelper to prefer local files over server
        // buildReadIntent is suspend (resolves download dir from DB)
        callback.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val readIntent = withContext(Dispatchers.IO) {
                    GalleryOpenHelper.buildReadIntent(activity, downloadInfo)
                }
                callback.launchGallery(readIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build read intent", e)
            }
        }
        return true
    }

    private fun openImportedArchive(
        activity: android.app.Activity,
        context: Context,
        downloadInfo: DownloadInfo
    ): Boolean {
        val archiveUri = Uri.parse(downloadInfo.archiveUri)
        try {
            // Test if we can access the URI
            context.contentResolver.openInputStream(archiveUri)?.use { testStream ->
                @Suppress("SENSELESS_COMPARISON")
                if (testStream == null) {
                    Toast.makeText(context, R.string.archive_not_accessible, Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        } catch (e: SecurityException) {
            // Try to restore permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    archiveUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ex: Exception) {
                Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show()
                Analytics.recordException(ex)
                return true
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.archive_not_accessible, Toast.LENGTH_SHORT).show()
            return true
        }

        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.data = archiveUri
        callback.launchGallery(intent)
        return true
    }

    /**
     * Processes the result from [GalleryActivity]. Updates spider info cache
     * and notifies the adapter of the changed item.
     */
    fun updateReadProcess(result: ActivityResult) {
        if (result.resultCode != DownloadsScene.LOCAL_GALLERY_INFO_CHANGE) return

        val data = result.data ?: return
        @Suppress("DEPRECATION")
        val info = data.getParcelableExtra<GalleryInfo>("info") ?: return

        // Check if this is an imported archive - skip SpiderInfo processing
        var isImportedArchive = false
        if (info is DownloadInfo) {
            isImportedArchive = info.archiveUri != null &&
                info.archiveUri!!.startsWith("content://")
        }

        if (!isImportedArchive) {
            // Only process SpiderInfo for regular downloads, not imported archives
            callback.viewModel.removeSpiderInfo(info.gid)
            val gid = info.gid
            callback.viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val spiderInfo = withContext(Dispatchers.IO) {
                        SpiderInfo.getSpiderInfo(info)
                    }
                    if (spiderInfo != null) {
                        callback.viewModel.putSpiderInfo(gid, spiderInfo)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load spider info", e)
                }
            }
        }

        val list = callback.mList ?: return
        val adapter = callback.mAdapter ?: return
        for (i in list.indices) {
            if (list[i].gid == info.gid) {
                val position = callback.listIndexInPage(i)
                adapter.notifyItemChanged(position)
                return
            }
        }
        // If item not found in current page, no notification needed
    }

    companion object {
        private const val TAG = "DownloadGalleryOpenHelper"
    }
}
