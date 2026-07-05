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
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.LRRMiscApi
import com.hippo.ehviewer.ui.scene.BaseScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        /**
         * Launches an archive file picker. The Scene-side implementation is
         * expected to forward [intent] to a previously-registered
         * `ActivityResultLauncher` and invoke [onPicked] with the picked Uri
         * (or `null` when the user cancels).
         */
        fun pickArchive(intent: Intent, onPicked: (Uri?) -> Unit)

        /**
         * Hand the prepared [request] to the ViewModel, which owns the upload
         * orchestration (pre-upload dedup + progress) and emits UI state the
         * Scene observes. The helper only resolves the Android [Uri]; it does
         * not perform the transfer itself.
         */
        fun startUpload(request: GalleryListViewModel.UploadRequest)
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
            mCallback.pickArchive(
                Intent.createChooser(
                    intent,
                    mCallback.getHostString(R.string.lrr_upload_choose_file)
                )
            ) { uri -> if (uri != null) handleUploadResult(uri) }
        } catch (e: Exception) {
            mCallback.showTip(R.string.lrr_upload_no_file_manager, BaseScene.LENGTH_SHORT)
        }
    }

    /**
     * Resolve the picked [uri] into an [GalleryListViewModel.UploadRequest] and
     * hand it to the ViewModel. The application context is used so the upload —
     * which runs in the ViewModel scope and can outlive this Scene's view — does
     * not capture an Activity.
     */
    fun handleUploadResult(uri: Uri) {
        val context = mCallback.getHostContext()?.applicationContext ?: return
        val owner = mCallback.getHostActivity() as? ComponentActivity ?: return
        // getFileNameFromUri does a ContentResolver.query, which can block on a
        // slow provider (cloud / SAF). Resolve the name off the main thread,
        // then build the request and start the upload back on main.
        owner.lifecycleScope.launch {
            val fileName = withContext(Dispatchers.IO) { getFileNameFromUri(context, uri) } ?: "upload_archive"
            val request = GalleryListViewModel.UploadRequest(
                displayName = fileName,
                cacheDir = context.cacheDir,
                openStream = { context.contentResolver.openInputStream(uri) }
            )
            mCallback.startUpload(request)
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
                    val jobId = LRRMiscApi.downloadUrl(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        url, null
                    )

                    val activity = mCallback.getHostActivity()
                    activity?.runOnUiThread {
                        mCallback.showTip(
                            mCallback.getHostString(R.string.lrr_url_download_success, jobId),
                            BaseScene.LENGTH_LONG
                        )
                    }
                } catch (ce: CancellationException) {
                    // Lifecycle teardown cancelled the request; not an error to tip.
                    throw ce
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
