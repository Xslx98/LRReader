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
package com.hippo.ehviewer.ui.fragment

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.util.Log
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import com.hippo.yorozuya.IOUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private var mDownloadLocation: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.download_settings)

        val mediaScan = findPreference<Preference>(DownloadSettings.KEY_MEDIA_SCAN)
        val downloadTimeout = findPreference<Preference>(DownloadSettings.KEY_DOWNLOAD_TIMEOUT)
        mDownloadLocation = findPreference(KEY_DOWNLOAD_LOCATION)
        val exportDownloadItems = findPreference<Preference>(KEY_EXPORT_DOWNLOAD_ITEMS)
        val importDownloadItems = findPreference<Preference>(KEY_IMPORT_DOWNLOAD_ITEMS)
        val cleanInvalidDownload = findPreference<Preference>(KEY_CLEAN_INVALID_DOWNLOAD)
        val preloadImage = findPreference<Preference>("preload_image")

        onUpdateDownloadLocation()

        // Initialize summaries with current settings
        if (downloadTimeout != null) {
            val timeoutStr = if (DownloadSettings.getDownloadTimeout() == 0) {
                getString(R.string.download_timeout_unlimited)
            } else {
                DownloadSettings.getDownloadTimeout().toString()
            }
            downloadTimeout.summary = getString(R.string.settings_download_timeout_summary, timeoutStr)
        }
        if (preloadImage != null) {
            preloadImage.summary = getString(
                R.string.settings_download_preload_image_summary,
                DownloadSettings.getPreloadImage().toString()
            )
        }

        mediaScan?.onPreferenceChangeListener = this
        downloadTimeout?.onPreferenceChangeListener = this

        mDownloadLocation?.onPreferenceClickListener = this
        exportDownloadItems?.onPreferenceClickListener = this
        importDownloadItems?.onPreferenceClickListener = this
        cleanInvalidDownload?.onPreferenceClickListener = this
    }

    override fun onDestroy() {
        super.onDestroy()
        mDownloadLocation = null
    }

    fun onUpdateDownloadLocation() {
        val file = DownloadSettings.getDownloadLocation()
        if (mDownloadLocation != null) {
            if (file != null) {
                mDownloadLocation!!.summary = file.uri.toString()
            } else {
                mDownloadLocation!!.setSummary(R.string.settings_download_invalid_download_location)
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val key = preference.key
        when (key) {
            KEY_DOWNLOAD_LOCATION -> {
                openDirPickerL()
                return true
            }
            KEY_EXPORT_DOWNLOAD_ITEMS -> {
                exportDownloadItems()
                return true
            }
            KEY_IMPORT_DOWNLOAD_ITEMS -> {
                importDownloadItems()
                return true
            }
            KEY_CLEAN_INVALID_DOWNLOAD -> {
                AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_download_clean_invalid_download)
                    .setMessage(R.string.settings_download_clean_invalid_download_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> executeCleanInvalidDownload() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return false
    }

    private fun openDirPickerL() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE_DIR_L)
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Toast.makeText(activity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportDownloadItems() {
        val list = ServiceRegistry.dataModule.downloadManager.downloadInfoList
        if (list.isEmpty()) {
            Toast.makeText(activity, R.string.settings_download_export_no_items, Toast.LENGTH_SHORT).show()
            return
        }

        val dir = DownloadSettings.getDownloadLocation()
        if (dir == null) {
            Toast.makeText(activity, R.string.settings_download_invalid_download_location, Toast.LENGTH_SHORT).show()
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
        val fileName = "lrreader-download-${sdf.format(Date())}.csv"

        val file = dir.createFile(fileName)
        if (file == null) {
            Toast.makeText(activity, R.string.settings_download_export_failed, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            file.openOutputStream().use { os ->
                os.write(DownloadManager.DOWNLOAD_INFO_HEADER.toByteArray(StandardCharsets.UTF_8))
                for (gi in list) {
                    os.write(gi.toCSV().toByteArray(StandardCharsets.UTF_8))
                }
            }
            Toast.makeText(
                activity,
                getString(R.string.settings_download_export_succeed, file.uri.toString()),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: IOException) {
            Toast.makeText(activity, R.string.settings_download_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importDownloadItems() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE)
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            Toast.makeText(activity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data == null) {
            super.onActivityResult(requestCode, resultCode, null)
            return
        }
        when (requestCode) {
            REQUEST_CODE_PICK_IMAGE_DIR -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uniFile = UniFile.fromUri(activity, data.data)
                    if (uniFile != null) {
                        DownloadSettings.putDownloadLocation(uniFile)
                        onUpdateDownloadLocation()
                    } else {
                        Toast.makeText(
                            activity,
                            R.string.settings_download_cant_get_download_location,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            REQUEST_CODE_PICK_IMAGE_DIR_L -> {
                if (resultCode == Activity.RESULT_OK) {
                    val treeUri = data.data
                    if (treeUri != null) {
                        requireActivity().contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        val uniFile = UniFile.fromTreeUri(activity, treeUri)
                        if (uniFile != null) {
                            DownloadSettings.putDownloadLocation(uniFile)
                            onUpdateDownloadLocation()
                        } else {
                            Toast.makeText(
                                activity,
                                R.string.settings_download_cant_get_download_location,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE -> {
                if (resultCode == Activity.RESULT_OK) {
                    executeImportDownload(data.data)
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        when (key) {
            DownloadSettings.KEY_MEDIA_SCAN -> {
                if (newValue is Boolean) {
                    val downloadLocation = DownloadSettings.getDownloadLocation()
                    if (newValue) {
                        CommonOperations.removeNoMediaFile(downloadLocation)
                        triggerMediaScan(downloadLocation)
                    } else {
                        CommonOperations.ensureNoMediaFile(downloadLocation)
                    }
                }
                return true
            }
            DownloadSettings.KEY_DOWNLOAD_TIMEOUT -> {
                if (newValue is String) {
                    DownloadSettings.setDownloadTimeout(toTimeoutTime(newValue))
                }
                return true
            }
        }
        return false
    }

    private fun toTimeoutTime(newValue: Any): Int {
        return try {
            newValue.toString().toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    // --- Import download task ---

    @Suppress("DEPRECATION")
    private fun executeImportDownload(uri: Uri?) {
        if (activity == null || uri == null) return

        val dialog = ProgressDialog(activity)
        dialog.setTitle(R.string.settings_download_import_items)
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var importCount = 0
            try {
                requireActivity().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val content = IOUtils.readString(inputStream, StandardCharsets.UTF_8.name())
                    val lines = content.split("\n")
                    val galleryInfos = lines.mapNotNull { line ->
                        if (line.startsWith(DownloadManager.DOWNLOAD_INFO_HEADER)) {
                            null
                        } else {
                            com.hippo.ehviewer.client.data.GalleryInfo.fromCSV(line)
                        }
                    }

                    val downloadManager = ServiceRegistry.dataModule.downloadManager
                    val total = galleryInfos.size
                    mainHandler.post { dialog.max = total; dialog.progress = 0 }

                    for (i in galleryInfos.indices) {
                        val gi = galleryInfos[i]
                        if (downloadManager.getDownloadInfo(gi.gid) == null) {
                            downloadManager.addDownload(gi, null)
                            importCount++
                        }
                        val progress = i + 1
                        mainHandler.post { dialog.progress = progress }
                    }
                } ?: run {
                    mainHandler.post { dismissAndShowResult(dialog, 0) }
                    return@launch
                }
            } catch (e: IOException) {
                // importCount stays 0
            }
            val result = importCount
            mainHandler.post { dismissAndShowResult(dialog, result) }
        }
    }

    @Suppress("DEPRECATION")
    private fun dismissAndShowResult(dialog: ProgressDialog, result: Int) {
        if (isAdded && activity != null) {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: IllegalArgumentException) {
                ExceptionUtils.throwIfFatal(e)
            }
            if (result > 0) {
                Toast.makeText(
                    activity,
                    getString(R.string.settings_download_import_succeed, result),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(activity, R.string.settings_download_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Clean invalid download task ---

    @Suppress("DEPRECATION")
    private fun executeCleanInvalidDownload() {
        if (activity == null) return

        val dialog = ProgressDialog(activity)
        dialog.setTitle(R.string.settings_download_cleaning)
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            var invalidCount = 0

            val downloadDir = DownloadSettings.getDownloadLocation()
            if (downloadDir == null || !downloadDir.isDirectory) {
                mainHandler.post { dismissAndShowCleanResult(dialog, 0) }
                return@launch
            }

            val files = downloadDir.listFiles()
            if (files == null) {
                mainHandler.post { dismissAndShowCleanResult(dialog, 0) }
                return@launch
            }

            val total = files.size
            mainHandler.post { dialog.max = total; dialog.progress = 0 }

            val downloadManager = ServiceRegistry.dataModule.downloadManager

            for (i in files.indices) {
                val dir = files[i]
                val progress = i + 1
                mainHandler.post { dialog.progress = progress }

                if (!dir.isDirectory) {
                    continue
                }

                val subFiles = dir.listFiles()
                if (subFiles == null || subFiles.isEmpty()) {
                    logs.add("Empty directory: ${dir.name}")
                    invalidCount++
                    dir.delete()
                    continue
                }

                val ehViewerFile = dir.findFile(DownloadManager.DOWNLOAD_INFO_FILENAME)
                if (ehViewerFile == null) {
                    logs.add("Missing .ehviewer file: ${dir.name}")
                    invalidCount++
                    continue
                }

                try {
                    val content = IOUtils.readString(
                        ehViewerFile.openInputStream(), StandardCharsets.UTF_8.name()
                    )
                    val contentLines = content.split("\n")
                    if (contentLines.size < 8) {
                        logs.add("Invalid .ehviewer file: ${dir.name}")
                        invalidCount++
                        val gid = try {
                            contentLines[0].toLong()
                        } catch (e: NumberFormatException) {
                            -1L
                        }
                        if (gid != -1L) {
                            val gi = downloadManager.getDownloadInfo(gid)
                            if (gi != null) {
                                gi.state = DownloadInfo.STATE_NONE
                                EhDB.putDownloadInfoAsync(gi)
                            }
                        }
                        continue
                    }
                    val pageCount = contentLines[7].toInt()
                    var imageFileCount = 0
                    for (subFile in subFiles) {
                        val name = subFile.name
                        if (name != null && !name.startsWith(".")) {
                            imageFileCount++
                        }
                    }

                    if (imageFileCount != pageCount) {
                        logs.add(
                            "Inconsistent file count: ${dir.name}, expected: $pageCount, actual: $imageFileCount"
                        )
                        invalidCount++
                        for (subFile in subFiles) {
                            val name = subFile.name
                            if (name != null && name != DownloadManager.DOWNLOAD_INFO_FILENAME && !name.startsWith(".")) {
                                subFile.delete()
                            }
                        }
                        val gid = try {
                            contentLines[0].toLong()
                        } catch (e: NumberFormatException) {
                            -1L
                        }
                        if (gid != -1L) {
                            val gi = downloadManager.getDownloadInfo(gid)
                            if (gi != null) {
                                gi.state = DownloadInfo.STATE_NONE
                                EhDB.putDownloadInfoAsync(gi)
                            }
                        }
                    }
                } catch (e: IOException) {
                    logs.add("Error processing directory: ${dir.name} - ${e.message}")
                    invalidCount++
                } catch (e: NumberFormatException) {
                    logs.add("Error processing directory: ${dir.name} - ${e.message}")
                    invalidCount++
                }
            }

            if (logs.isNotEmpty()) {
                saveCleanLog(downloadDir, logs)
            }

            val resultCount = invalidCount
            mainHandler.post { dismissAndShowCleanResult(dialog, resultCount) }
        }
    }

    @Suppress("DEPRECATION")
    private fun dismissAndShowCleanResult(dialog: ProgressDialog, result: Int) {
        if (isAdded && activity != null) {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: IllegalArgumentException) {
                ExceptionUtils.throwIfFatal(e)
            }
            if (result > 0) {
                Toast.makeText(
                    activity,
                    getString(R.string.settings_download_clean_invalid_done, result),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    activity,
                    R.string.settings_download_clean_invalid_no_invalid,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveCleanLog(downloadDir: UniFile, logs: List<String>) {
        val sdf = SimpleDateFormat("yyyyMMddHHmm", Locale.US)
        val fileName = "delfile-${sdf.format(Date())}.log"
        val logFile = downloadDir.createFile(fileName)
        if (logFile != null) {
            try {
                logFile.openOutputStream().use { os ->
                    for (log in logs) {
                        os.write("$log\n".toByteArray(StandardCharsets.UTF_8))
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Save clean log file", e)
            }
        }
    }

    /**
     * Trigger MediaStore re-scan for all image files in the download directory.
     */
    private fun triggerMediaScan(downloadLocation: UniFile?) {
        if (downloadLocation == null) {
            return
        }
        val activityRef = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            collectImagePaths(downloadLocation, paths)
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    activityRef.applicationContext,
                    paths.toTypedArray(),
                    null,
                    null
                )
            }
        }
    }

    private fun collectImagePaths(dir: UniFile?, paths: MutableList<String>) {
        if (dir == null || !dir.isDirectory) {
            return
        }
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                collectImagePaths(f, paths)
            } else {
                val name = f.name
                if (name != null) {
                    val lower = name.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".gif") ||
                        lower.endsWith(".webp")
                    ) {
                        val uri = f.uri
                        if (uri != null && "file" == uri.scheme) {
                            val path = uri.path
                            if (path != null) {
                                paths.add(path)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DownloadFragment"
        const val REQUEST_CODE_PICK_IMAGE_DIR = 0
        const val REQUEST_CODE_PICK_IMAGE_DIR_L = 1
        private const val REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE = 2

        const val KEY_DOWNLOAD_LOCATION = "download_location"
        const val KEY_EXPORT_DOWNLOAD_ITEMS = "export_download_items"
        const val KEY_IMPORT_DOWNLOAD_ITEMS = "import_download_items"
        const val KEY_CLEAN_INVALID_DOWNLOAD = "clean_invalid_download"

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
