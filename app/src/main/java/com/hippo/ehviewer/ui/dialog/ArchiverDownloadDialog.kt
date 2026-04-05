package com.hippo.ehviewer.ui.dialog

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.ArchiverData
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.exception.NoHAtHClientException
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.SceneFragment
import com.hippo.unifile.UniFile
import com.hippo.util.FileUtils
import java.io.File
import java.util.Arrays

class ArchiverDownloadDialog(
    private val galleryDetail: GalleryDetail,
    private val detailScene: GalleryDetailScene
) : DialogInterface.OnDismissListener, EhClient.Callback<ArchiverData> {

    private val context: Context = detailScene.ehContext!!
    private val downloadReceiver = DownloadReceiver(galleryDetail)

    private var dialog: Dialog? = null

    private var currentFunds: TextView? = null
    private var originalCost: TextView? = null
    private var originalSize: TextView? = null
    private var resampleCost: TextView? = null
    private var resampleSize: TextView? = null
    private var resampleDownload: Button? = null
    private var originalDownload: Button? = null

    private var progressBar: ProgressBar? = null
    private var body: LinearLayout? = null

    private var myDownloadId: Long = 0

    private var data = ArchiverData()

    fun showDialog() {
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_archiver_title)
            .setView(R.layout.dialog_archiver)
            .setOnDismissListener(this)
            .show()
        currentFunds = dialog!!.findViewById(R.id.dialog_archiver_current_funds)
        originalCost = dialog!!.findViewById(R.id.dialog_archiver_original_cost)
        originalSize = dialog!!.findViewById(R.id.dialog_archiver_original_size)
        resampleCost = dialog!!.findViewById(R.id.dialog_archiver_resample_cost)
        resampleSize = dialog!!.findViewById(R.id.dialog_archiver_resample_size)
        resampleDownload = dialog!!.findViewById(R.id.dialog_archiver_resample_download)
        originalDownload = dialog!!.findViewById(R.id.dialog_archiver_original_download)
        progressBar = dialog!!.findViewById(R.id.dialog_archiver_progress)
        body = dialog!!.findViewById(R.id.dialog_archiver_body)
        resampleDownload!!.setOnClickListener { v -> onArchiverDownload(v) }
        originalDownload!!.setOnClickListener { v -> onArchiverDownload(v) }
        val mRequest = EhRequest().setMethod(EhClient.METHOD_ARCHIVER)
            .setArgs(galleryDetail.archiveUrl, galleryDetail.gid, galleryDetail.token)
            .setCallback(this)
        ServiceRegistry.clientModule.ehClient.execute(mRequest!!)
    }

    private fun onArchiverDownload(view: View) {
        try {
            var url: String? = null
            var dltype: String? = null
            var dlcheck: String? = null
            if (view === originalDownload) {
                url = data.originalUrl
                dltype = "org"
                dlcheck = "Download Original Archive"
            } else if (view === resampleDownload) {
                url = data.resampleUrl
                dltype = "res"
                dlcheck = "Download Resample Archive"
            }
            if (url == null) {
                return
            }
            val activity = detailScene.activity2
            if (context != null && activity != null) {
                val request = EhRequest()
                request.setMethod(EhClient.METHOD_DOWNLOAD_ARCHIVER)
                request.setArgs(url, galleryDetail.archiveUrl, dltype, dlcheck)
                request.setCallback(
                    DownloadArchiverListener(
                        context, activity.stageId, detailScene.tag, this
                    )
                )
                ServiceRegistry.clientModule.ehClient.execute(request)
            }
        } finally {
            progressBar!!.visibility = View.VISIBLE
            body!!.visibility = View.INVISIBLE
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
    }

    override fun onSuccess(result: ArchiverData) {
        data = result
        val cF = if (AppearanceSettings.getGallerySite() == EhUrl.SITE_E) {
            context.getString(R.string.archiver_dialog_current_funds) + data.funds
        } else {
            data.funds
        }

        currentFunds!!.text = cF
        val oC = context.getString(R.string.archiver_dialog_cost, data.originalCost)
        val rC = context.getString(R.string.archiver_dialog_cost, data.resampleCost)
        originalCost!!.text = oC
        resampleCost!!.text = rC
        val oS = context.getString(R.string.archiver_dialog_size, data.originalSize)
        val rS = context.getString(R.string.archiver_dialog_size, data.resampleSize)
        originalSize!!.text = oS
        resampleSize!!.text = rS
        progressBar!!.visibility = View.GONE
        body!!.visibility = View.VISIBLE
    }

    override fun onFailure(e: Exception) {
    }

    override fun onCancel() {
    }

    private inner class DownloadArchiverListener(
        context: Context,
        stageId: Int,
        sceneTag: String?,
        private val archiverDownloadDialog: ArchiverDownloadDialog
    ) : EhCallback<GalleryDetailScene, String>(context, stageId, sceneTag) {

        private val context: Context = context

        override fun onSuccess(result: String) {
            val downloadUrl: String = result
            if (dialog != null && !dialog!!.isShowing) {
                return
            }
            if (downloadUrl.isBlank()) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show()
                return
            }
            progressBar!!.visibility = View.INVISIBLE
            body!!.visibility = View.VISIBLE
            dialog!!.dismiss()
            showTip(R.string.download_archive_started, BaseScene.LENGTH_SHORT)
            val fileName = createFileName(galleryDetail.title, galleryDetail.gid)
            if (fileName.isEmpty()) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show()
                return
            }
            val downloadUri = Uri.parse(downloadUrl)
            val scheme = downloadUri.scheme
            if (!"http".equals(scheme, ignoreCase = true) && !"https".equals(scheme, ignoreCase = true)) {
                Log.w("ArchiverDownloadDialog", "Invalid download URL scheme: $downloadUrl")
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show()
                return
            }
            val request: android.app.DownloadManager.Request
            try {
                request = android.app.DownloadManager.Request(downloadUri)
            } catch (e: IllegalArgumentException) {
                Log.e("ArchiverDownloadDialog", "Invalid download URL: $downloadUrl", e)
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show()
                return
            }
            request.setAllowedNetworkTypes(
                android.app.DownloadManager.Request.NETWORK_MOBILE or
                        android.app.DownloadManager.Request.NETWORK_WIFI
            )
            request.setAllowedOverRoaming(true)
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            request.setTitle(galleryDetail.title)
            request.setDescription(context.getString(R.string.download_archive_started))
            request.setVisibleInDownloadsUi(true)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                EhConfig.ARCHIVER_PATH + fileName + ".zip"
            )
            @Suppress("DEPRECATION")
            request.allowScanningByMediaScanner()

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            if (downloadManager == null) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show()
                return
            }

            myDownloadId = downloadManager.enqueue(request)
            Settings.putArchiverDownloadId(galleryDetail.gid, myDownloadId)
            Settings.putArchiverDownload(myDownloadId, galleryDetail)
            detailScene.bindArchiverProgress(galleryDetail)

            ContextCompat.registerReceiver(
                context, downloadReceiver,
                IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        override fun onFailure(e: Exception) {
            if (dialog!!.isShowing) {
                dialog!!.dismiss()
            }
            if (e is NoHAtHClientException) {
                showTip(R.string.download_h_h_failure_no_hath, BaseScene.LENGTH_LONG)
            } else {
                showTip(R.string.download_archive_failure, BaseScene.LENGTH_LONG)
            }
        }

        override fun onCancel() {
        }

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is GalleryDetailScene
        }
    }

    private inner class DownloadReceiver(
        private val galleryDetail: GalleryDetail
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                val downloadId = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                if (myDownloadId != downloadId) {
                    return
                }
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                checkDownloadStatus(downloadId, downloadManager)
            }
        }

        private fun checkDownloadStatus(downloadId: Long, downloadManager: android.app.DownloadManager) {
            val query = android.app.DownloadManager.Query()
            query.setFilterById(downloadId)
            var c: Cursor? = null
            try {
                c = downloadManager.query(query)
                if (c != null && c.moveToFirst()) {
                    val status = c.getInt(
                        c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)
                    )
                    when (status) {
                        android.app.DownloadManager.STATUS_PAUSED ->
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>下载暂停")
                        android.app.DownloadManager.STATUS_PENDING ->
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>下载延迟")
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>下载完成")
                            unzipAndImportFile(c)
                        }
                        android.app.DownloadManager.STATUS_FAILED ->
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>下载失败")
                        android.app.DownloadManager.STATUS_RUNNING ->
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>正在下载")
                        else ->
                            Log.i(DOWNLOAD_RECEIVER_TAG, ">>>正在下载")
                    }
                }
            } catch (e: IllegalArgumentException) {
                Log.e(DOWNLOAD_RECEIVER_TAG, e.message ?: "", e)
            } catch (e: java.net.URISyntaxException) {
                Log.e(DOWNLOAD_RECEIVER_TAG, e.message ?: "", e)
            } catch (e: NullPointerException) {
                Log.e(DOWNLOAD_RECEIVER_TAG, e.message ?: "", e)
            } finally {
                c?.close()
            }
        }

        @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod")
        private fun unzipAndImportFile(cursor: Cursor) {
            val path = cursor.getString(
                cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_LOCAL_URI)
            )
            val uri = Uri.parse(path)
            val tempDir = AppConfig.getExternalTempDir() ?: return
            val downloadId = cursor.getLong(
                cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID)
            )
            val fileName = createFileName(galleryDetail.title, galleryDetail.gid)
            val tempFilePath = "${tempDir.path}/$fileName"

            // Handle content:// URI by copying to temp file first
            com.hippo.util.IoThreadPoolExecutor.instance.execute {
                var tempZipFile: File? = null
                try {
                    val zipFilePath: String
                    if ("file" == uri.scheme) {
                        val zipFile = File(uri.path!!)
                        zipFilePath = zipFile.path
                    } else {
                        tempZipFile = File(tempDir, "$fileName.zip")
                        val sourceFile = UniFile.fromUri(context, uri)
                        if (sourceFile == null) {
                            Log.e(DOWNLOAD_RECEIVER_TAG, "Cannot access source file: $uri")
                            return@execute
                        }
                        val destFile = UniFile.fromFile(tempZipFile)
                        if (destFile == null) {
                            Log.e(DOWNLOAD_RECEIVER_TAG, "Cannot create temp zip file")
                            return@execute
                        }
                        if (!FileUtils.copyFile(sourceFile, destFile, false)) {
                            Log.e(DOWNLOAD_RECEIVER_TAG, "Failed to copy zip file to temp location")
                            return@execute
                        }
                        zipFilePath = tempZipFile.path
                    }

                    val result = com.hippo.ehviewer.util.GZIPUtils.UnZipFolder(zipFilePath, tempFilePath)
                    if (!result) {
                        return@execute
                    }
                    importGallery(tempFilePath, downloadId)
                } catch (e: Exception) {
                    Log.e(DOWNLOAD_RECEIVER_TAG, "Error in unzipAndImportFile", e)
                } finally {
                    if (tempZipFile != null && tempZipFile.exists()) {
                        tempZipFile.delete()
                    }
                }
            }
        }

        private fun importGallery(tempFilePath: String, downloadId: Long) {
            if (tempFilePath.isEmpty()) {
                return
            }

            val tempFile = File(tempFilePath)
            val tempPictures = tempFile.listFiles() ?: return
            Arrays.sort(tempPictures) { file1, file2 ->
                file1.name.compareTo(file2.name)
            }

            val spiderDen = SpiderDen(galleryDetail)
            spiderDen.setMode(SpiderQueen.MODE_DOWNLOAD)
            val downloadDir = spiderDen.getDownloadDir() ?: return

            try {
                for (i in tempPictures.indices) {
                    val picture = tempPictures[i]
                    val pictureName = picture.name
                    val nameArr = pictureName.split("\\.".toRegex())
                    val newName = SpiderDen.generateImageFilename(i, ".${nameArr[nameArr.size - 1]}")

                    // Use UniFile API instead of File
                    var destFile = downloadDir.findFile(newName)
                    if (destFile != null && destFile.exists()) {
                        if (!destFile.delete()) {
                            continue
                        }
                    }

                    destFile = downloadDir.createFile(newName)
                    if (destFile == null) {
                        Log.e(DOWNLOAD_RECEIVER_TAG, "Failed to create file: $newName")
                        continue
                    }

                    val sourceFile = UniFile.fromFile(picture)
                    if (sourceFile == null || !FileUtils.copyFile(sourceFile, destFile, false)) {
                        Log.e(DOWNLOAD_RECEIVER_TAG, "Failed to copy file: ${picture.name} to $newName")
                        destFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(DOWNLOAD_RECEIVER_TAG, "Error in importGallery", e)
            }
            val deleteTemp = tempFile.delete()
            if (!deleteTemp) {
                tempFile.deleteOnExit()
            }
            val finalFileName = tempFile.name
            Handler(Looper.getMainLooper()).post {
                val labelName = context.getString(R.string.download_label_archiver)
                val manager = ServiceRegistry.dataModule.downloadManager
                manager.addLabel(labelName)
                manager.addDownload(galleryDetail, labelName, DownloadInfo.STATE_FINISH)
                Toast.makeText(
                    context,
                    context.getString(R.string.stat_download_done_line_succeeded, finalFileName),
                    Toast.LENGTH_LONG
                ).show()
                try {
                    context.unregisterReceiver(downloadReceiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered
                }
                val info = Settings.getArchiverDownload(downloadId)
                if (info == null) {
                    return@post
                }
                Settings.deleteArchiverDownloadId(info.gid)
                Settings.deleteArchiverDownload(downloadId)
            }
        }

    }

    companion object {
        private const val DOWNLOAD_RECEIVER_TAG = "DownloadReceiver"
        /**
         * 统一净化归档文件名，避免 DownloadManager 因非法路径抛错。
         */
        @JvmStatic
        private fun createFileName(name: String?, gid: Long): String {
            var result = if (name == null) "" else com.hippo.lib.yorozuya.FileUtils.sanitizeFilename(name)
            val maxFilenameLength = 150
            if (result.length > maxFilenameLength) {
                result = result.substring(0, maxFilenameLength)
            }
            if (result.isEmpty()) {
                result = if (gid > 0) "archiver_$gid" else "archiver"
            }
            return result
        }
    }
}
