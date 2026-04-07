package com.hippo.ehviewer.ui.scene.gallery.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hippo.app.EditTextDialogBuilder
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.lrr.LRRArchiveApi
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.client.lrr.LRRMiscApi
import com.hippo.ehviewer.client.lrr.runSuspend
import com.hippo.ehviewer.ui.scene.BaseScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Handles archive upload and URL download operations for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryUploadHelper(private val mCallback: Callback) {

    interface Callback {
        fun showTip(message: String, length: Int)
        fun showTip(resId: Int, length: Int)
        fun refreshList()
        fun getHostActivity(): Activity?
        fun getHostContext(): Context?
        fun getHostString(resId: Int): String
        fun getHostString(resId: Int, vararg formatArgs: Any): String
        fun startActivityForResult(intent: Intent, requestCode: Int)
    }

    /**
     * Launch file picker for archive upload (ZIP, RAR, CBZ, CB7, etc.).
     */
    fun showUploadFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        val mimeTypes = arrayOf(
            "application/zip", "application/x-rar-compressed",
            "application/x-7z-compressed", "application/x-tar",
            "application/gzip", "application/octet-stream"
        )
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            mCallback.startActivityForResult(
                Intent.createChooser(
                    intent,
                    mCallback.getHostString(R.string.lrr_upload_choose_file)
                ),
                GalleryListScene.REQUEST_CODE_UPLOAD_ARCHIVE
            )
        } catch (e: Exception) {
            mCallback.showTip(R.string.lrr_upload_no_file_manager, BaseScene.LENGTH_SHORT)
        }
    }

    /**
     * Handle the selected file for archive upload.
     */
    fun handleUploadResult(uri: Uri) {
        val context = mCallback.getHostContext() ?: return
        val owner = mCallback.getHostActivity() as? ComponentActivity ?: return

        owner.lifecycleScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                var fileName = getFileNameFromUri(context, uri)
                if (fileName == null) fileName = "upload_archive"
                tempFile = File(context.cacheDir, fileName)
                context.contentResolver.openInputStream(uri).use { inputStream ->
                    FileOutputStream(tempFile).use { fos ->
                        if (inputStream == null) throw IOException("Cannot open file")
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }

                val finalTempFile = tempFile
                val arcid = runSuspend {
                    LRRArchiveApi.uploadArchive(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        finalTempFile, null, null, null
                    )
                }

                val activity = mCallback.getHostActivity()
                activity?.runOnUiThread {
                    mCallback.showTip(
                        mCallback.getHostString(R.string.lrr_upload_success),
                        BaseScene.LENGTH_LONG
                    )
                    mCallback.refreshList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                val activity = mCallback.getHostActivity()
                activity?.runOnUiThread {
                    mCallback.showTip(
                        mCallback.getHostString(R.string.lrr_upload_failed, e.message ?: ""),
                        BaseScene.LENGTH_LONG
                    )
                }
            } finally {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    /**
     * Show dialog for URL download on the LANraragi server.
     */
    fun showUrlDownloadDialog() {
        val context = mCallback.getHostContext() ?: return

        val builder = EditTextDialogBuilder(
            context, null, mCallback.getHostString(R.string.lrr_url_download_hint)
        )
        builder.setTitle(mCallback.getHostString(R.string.lrr_url_download_title))
        builder.setPositiveButton(mCallback.getHostString(android.R.string.ok)) { dialog, _ ->
            val url = builder.text.trim()
            if (url.isEmpty()) {
                mCallback.showTip(R.string.lrr_url_download_empty, BaseScene.LENGTH_SHORT)
                return@setPositiveButton
            }
            val owner = mCallback.getHostActivity() as? ComponentActivity
                ?: return@setPositiveButton
            owner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jobId = runSuspend {
                        LRRMiscApi.downloadUrl(
                            LRRClientProvider.getClient(),
                            LRRClientProvider.getBaseUrl(),
                            url, null
                        )
                    }

                    val activity = mCallback.getHostActivity()
                    activity?.runOnUiThread {
                        mCallback.showTip(
                            mCallback.getHostString(R.string.lrr_url_download_success, jobId),
                            BaseScene.LENGTH_LONG
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "URL download failed", e)
                    val activity = mCallback.getHostActivity()
                    activity?.runOnUiThread {
                        mCallback.showTip(
                            mCallback.getHostString(R.string.lrr_url_download_failed, e.message ?: ""),
                            BaseScene.LENGTH_LONG
                        )
                    }
                }
            }
        }
        builder.show()
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    companion object {
        private const val TAG = "GalleryUploadHelper"
    }
}
