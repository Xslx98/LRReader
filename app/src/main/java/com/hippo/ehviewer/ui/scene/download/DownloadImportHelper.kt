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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleOwner
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.util.FileUtils

/**
 * Encapsulates the local archive import pipeline (file picker, URI permission,
 * archive validation, and DownloadInfo creation) extracted from DownloadsScene.
 */
class DownloadImportHelper(
    private val mCallback: Callback,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) {

    /**
     * Callback interface so the helper can interact with its host
     * (DownloadsScene) without a direct dependency.
     */
    interface Callback {
        fun getContext(): Context?
        fun getActivity(): Activity?
        fun getDownloadManager(): DownloadManager?
        fun getString(resId: Int): String?
        fun onImportSuccess()
    }

    private val mFilePickerLauncher: ActivityResultLauncher<Intent> = registry.register(
        REGISTRY_KEY,
        lifecycleOwner,
        ActivityResultContracts.StartActivityForResult(),
        ::handleSelectedFile
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Launches the system file picker for archive files.
     */
    fun importLocalArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
            ))
            addCategory(Intent.CATEGORY_OPENABLE)
            // Enable persistent URI permissions
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            val title = mCallback.getString(R.string.import_archive_title)
            mFilePickerLauncher.launch(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            showToast(R.string.import_archive_failed)
        }
    }

    // -------------------------------------------------------------------------
    // Internal pipeline
    // -------------------------------------------------------------------------

    private fun handleSelectedFile(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            return
        }

        val uri = result.data?.data ?: return
        val context = mCallback.getContext() ?: return

        // Request persistent URI permission immediately when file is selected
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "Successfully obtained persistent URI permission for: $uri")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: $uri", e)
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: $uri", e)
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            return
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show()

        // Process the archive file in background
        com.hippo.util.IoThreadPoolExecutor.instance.execute {
            processArchiveFile(uri)
        }
    }

    private fun processArchiveFile(uri: Uri) {
        val context = mCallback.getContext() ?: return

        try {
            // Verify URI accessibility (permission should already be granted)
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Stream is accessible
                } ?: run {
                    runOnUiThread {
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot access file even with persistent permission", e)
                runOnUiThread {
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                }
                return
            }

            // Get file name
            val fileName: String = FileUtils.getFileName(context, uri)
                ?: "imported_archive_${System.currentTimeMillis()}"

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                runOnUiThread {
                    Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                }
                return
            }

            // Create DownloadInfo for the archive
            val downloadInfo = createArchiveDownloadInfo(uri, fileName)
            if (downloadInfo == null) {
                runOnUiThread {
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                }
                return
            }

            // Check if already imported
            val downloadManager = mCallback.getDownloadManager()
            if (downloadManager != null && downloadManager.containDownloadInfo(downloadInfo.gid)) {
                runOnUiThread {
                    Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                }
                return
            }

            // Add to download manager
            if (downloadManager != null) {
                val downloadList = ArrayList<DownloadInfo>()
                downloadList.add(downloadInfo)
                downloadManager.addDownload(downloadList)
                runOnUiThread {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show()
                    mCallback.onImportSuccess()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process archive file", e)
            runOnUiThread {
                Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isValidArchiveFormat(fileName: String?): Boolean {
        if (fileName == null) return false
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr")
    }

    private fun createArchiveDownloadInfo(uri: Uri, fileName: String): DownloadInfo? {
        return try {
            DownloadInfo().apply {
                gid = System.currentTimeMillis() // Use timestamp as unique ID
                token = ""
                title = fileName.replace("\\.[^.]*$".toRegex(), "") // Remove extension
                titleJpn = null
                thumb = null // No thumbnail for imported archives
                category = EhUtils.UNKNOWN // Keep as UNKNOWN, will be handled in display logic
                posted = null
                uploader = "Local Archive"
                rating = -1.0f // Keep default rating to not affect other downloads
                state = DownloadInfo.STATE_FINISH
                legacy = 0
                time = System.currentTimeMillis()
                label = null
                total = 0 // Will be set by archive provider
                finished = 0

                // Store the URI in the archiveUri field - this is the key identifier
                archiveUri = uri.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DownloadInfo", e)
            null
        }
    }

    private fun runOnUiThread(runnable: Runnable) {
        mCallback.getActivity()?.runOnUiThread(runnable)
    }

    private fun showToast(resId: Int) {
        val context = mCallback.getContext()
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val TAG = DownloadImportHelper::class.java.simpleName
        private const val REGISTRY_KEY = "download_import_file_picker"
    }
}
