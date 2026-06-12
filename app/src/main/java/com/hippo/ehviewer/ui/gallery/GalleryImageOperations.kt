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

package com.hippo.ehviewer.ui.gallery

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.hippo.content.FileProvider
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.lanraragi.reader.domain.Archive
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import com.hippo.lib.yorozuya.IOUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Handles image save, share, and page dialog operations
 * for the gallery reader. Extracted from GalleryActivity.
 */
class GalleryImageOperations(private val mActivity: Activity) {

    var galleryProvider: GalleryProvider2? = null
    var archive: Archive? = null
    var saveToLauncher: ActivityResultLauncher<Intent>? = null

    private var mCacheFileName: String? = null

    // --- Share ---

    fun shareImage(page: Int) {
        val provider = galleryProvider ?: return

        val dir = AppConfig.getExternalTempDir()
        if (dir == null) {
            Toast.makeText(mActivity, R.string.error_cant_create_temp_file, Toast.LENGTH_SHORT).show()
            return
        }
        (mActivity as ComponentActivity).lifecycleScope.launch {
            // Extracting/copying the page is file I/O — keep it off the main thread.
            val file = withContext(Dispatchers.IO) {
                provider.save(page, UniFile.fromFile(dir)!!, provider.getImageFilename(page))
            }
            val filename = file?.name
            if (filename == null) {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
                return@launch
            }

            var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(filename)
            )
            if (TextUtils.isEmpty(mimeType)) {
                mimeType = "image/jpeg"
            }

            // Hand the share target a readable content:// URI. A file:// URI into the app's
            // external-files temp dir is unreadable to other apps on modern Android (and
            // only avoided a FileUriExposedException via a StrictMode hack).
            val uri = try {
                val authority = mActivity.application.packageName + ".fileprovider"
                FileProvider.getUriForFile(mActivity, authority, File(dir, filename))
            } catch (e: IllegalArgumentException) {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = mimeType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                mActivity.startActivity(
                    Intent.createChooser(intent, mActivity.getString(R.string.share_image))
                )
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                Toast.makeText(mActivity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Save to MediaStore ---

    fun saveImage(page: Int) {
        val provider = galleryProvider ?: return

        val cacheDir = mActivity.cacheDir
        if (cacheDir == null) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
            return
        }
        (mActivity as ComponentActivity).lifecycleScope.launch {
            val filename = provider.getImageFilename(page)
            val tempFile = withContext(Dispatchers.IO) {
                provider.save(page, UniFile.fromFile(cacheDir)!!, filename)
            }
            if (tempFile == null) {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Build display name with archive title prefix
            val archiveTitle = archive?.title?.takeIf { it.isNotEmpty() }
                ?.let { sanitizeFilename(it, 80) }
                ?: "archive"
            val displayName = archiveTitle + "_" + (filename ?: "page_$page")

            // Determine MIME type
            var mimeType: String? = null
            if (filename != null) {
                val ext = MimeTypeMap.getFileExtensionFromUrl(filename)
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }
            if (TextUtils.isEmpty(mimeType)) {
                mimeType = "image/jpeg"
            }

            // MediaStore insert + the full-file copy are I/O — run them off the main thread.
            val saved = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/LRReader"
                    )
                }
                val resolver = mActivity.contentResolver
                val mediaUri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: run {
                    File(cacheDir, filename ?: "").delete()
                    return@withContext false
                }
                var copied = false
                try {
                    tempFile.openInputStream()?.use { input ->
                        resolver.openOutputStream(mediaUri)?.use { output ->
                            IOUtils.copy(input, output)
                            copied = true
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy image to MediaStore", e)
                }
                if (!copied) {
                    resolver.delete(mediaUri, null, null)
                    return@withContext false
                }
                tempFile.delete()
                true
            }

            if (saved) {
                Toast.makeText(
                    mActivity,
                    mActivity.getString(R.string.image_saved, "Pictures/LRReader/$displayName"),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Save to user-chosen location (using ActivityResultLauncher) ---

    fun saveImageTo(page: Int) {
        val provider = galleryProvider ?: return
        val dir = mActivity.cacheDir
        (mActivity as ComponentActivity).lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                provider.save(page, UniFile.fromFile(dir)!!, provider.getImageFilename(page))
            }
            val filename = file?.name
            if (filename == null) {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
                return@launch
            }
            mCacheFileName = filename
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_TITLE, filename)
            }
            try {
                saveToLauncher?.launch(intent)
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                Toast.makeText(mActivity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Handle the result from the SAF "save to" picker.
     */
    fun handleSaveToResult(result: ActivityResult?) {
        if (result == null || result.resultCode != Activity.RESULT_OK) {
            return
        }
        val resultData = result.data ?: return
        val uri = resultData.data ?: return
        val filepath = mActivity.cacheDir.toString() + "/" + mCacheFileName
        val cacheFile = File(filepath)

        (mActivity as ComponentActivity).lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                var ok = false
                try {
                    FileInputStream(cacheFile).use { input ->
                        mActivity.contentResolver.openOutputStream(uri)?.use { output ->
                            IOUtils.copy(input, output)
                            ok = true
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy image to chosen location", e)
                }
                if (!cacheFile.delete()) {
                    cacheFile.deleteOnExit()
                }
                ok
            }

            // The copy can fail (destination unwritable, out of space, provider
            // error). Don't claim success / trigger a media scan when nothing was
            // written — that left users with a "saved" toast over an empty file.
            if (!success) {
                Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(
                mActivity,
                mActivity.getString(R.string.image_saved, uri.path),
                Toast.LENGTH_SHORT
            ).show()
            mActivity.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
        }
    }

    // --- Page dialog ---

    fun showPageDialog(page: Int) {
        val builder = AlertDialog.Builder(mActivity)
        builder.setTitle(mActivity.resources.getString(R.string.page_menu_title, page + 1))

        val items = arrayOf<CharSequence>(
            mActivity.getString(R.string.page_menu_refresh),
            mActivity.getString(R.string.page_menu_share),
            mActivity.getString(R.string.page_menu_save),
            mActivity.getString(R.string.page_menu_save_to)
        )

        builder.setItems(items) { _, which ->
            val provider = galleryProvider ?: return@setItems
            when (which) {
                0 -> { // Refresh
                    provider.removeCache(page)
                    provider.forceRequest(page)
                }
                1 -> shareImage(page) // Share
                2 -> saveImage(page)  // Save
                3 -> saveImageTo(page) // Save to
            }
        }

        val dialog = builder.show()
        applyImmersiveToDialog(dialog)
    }

    // --- Utility ---

    /**
     * Prevent edge-to-edge window insets from offsetting dialog content
     * when in fullscreen reading mode.
     */
    fun applyImmersiveToDialog(dialog: AlertDialog) {
        val window = dialog.window
        if (window != null && ReadingSettings.getReadingFullscreen()) {
            val decorView = window.decorView
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, _ ->
                WindowInsetsCompat.CONSUMED
            }
            decorView.requestApplyInsets()
        }
    }

    companion object {
        private const val TAG = "GalleryImageOperations"

        /**
         * Sanitize a string for use as a filename.
         */
        @JvmStatic
        fun sanitizeFilename(name: String?, maxLen: Int): String {
            if (name.isNullOrEmpty()) return "archive"
            var safe = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
            if (safe.isEmpty()) return "archive"
            if (safe.length > maxLen) safe = safe.substring(0, maxLen)
            return safe
        }
    }
}
