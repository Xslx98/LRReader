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
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleOwner
import com.hippo.ehviewer.R

/**
 * Manages the file picker ActivityResultLauncher for local archive imports.
 * The actual archive processing is handled by [DownloadsViewModel.processArchiveImport].
 *
 * No Callback interface — uses an explicit [onFileSelected] function parameter.
 */
class DownloadImportHelper(
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner,
    private val contextProvider: () -> Context?,
    private val onFileSelected: (android.net.Uri) -> Unit
) {

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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            val context = contextProvider()
            val title = context?.getString(R.string.import_archive_title)
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
        val context = contextProvider() ?: return

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

        // Show processing toast
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show()

        // Delegate to ViewModel via the callback
        onFileSelected(uri)
    }

    private fun showToast(resId: Int) {
        val context = contextProvider()
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val TAG = DownloadImportHelper::class.java.simpleName
        private const val REGISTRY_KEY = "download_import_file_picker"
    }
}
