package com.hippo.ehviewer.ui.dialog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.updater.GhRelease


class UpdateDialog(private val activity: Activity) {
    companion object {
        const val GITHUB_RELEASE_URL = "https://github.com/Xslx98/LRReader/releases"
        const val GITHUB_README_URL =
            "https://github.com/Xslx98/LRReader/blob/main/README.md"
        const val INSTALL_PERMISSION_CODE = 1002
    }

    private fun isActivityAlive(): Boolean {
        return !(activity.isFinishing || activity.isDestroyed)
    }

    fun showCheckFailDialog() {
        try {
            ContextCompat.getMainExecutor(activity).execute {
                if (!isActivityAlive()) {
                    return@execute
                }
                val alertDialog = AlertDialog.Builder(activity)
                    .setIcon(R.mipmap.ic_launcher)
                    .setTitle(R.string.update_fail)
                    .setMessage(R.string.update_fail_info)
                    .setPositiveButton(R.string.yes) { dialog, id ->
                        gotoGithub(dialog, id)
                    }
                    .setNegativeButton(R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                if (isActivityAlive()) {
                    alertDialog.show()
                }
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    fun showUpdateDialog(release: GhRelease) {
        try {
            val version = release.tagName
            val title = if (release.name.isNotBlank()) release.name else release.tagName
            // body lines for setItems (split GitHub markdown body into lines, dropping blanks)
            val contentLines = release.body
                .lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .toList()
                .toTypedArray()
            val downloadUrl = release.apkAsset?.browserDownloadUrl.orEmpty()

            ContextCompat.getMainExecutor(activity).execute {
                if (!isActivityAlive()) {
                    return@execute
                }
                val alertDialog = AlertDialog.Builder(activity).apply {
                    setIcon(R.mipmap.ic_launcher)
                    setTitle(title)
                    if (contentLines.isNotEmpty()) {
                        setItems(contentLines) { _, _ -> /* informational only */ }
                    } else if (release.body.isNotBlank()) {
                        setMessage(release.body)
                    }
                    setPositiveButton(R.string.update) { dialog, id ->
                        downloadApk(dialog, id, downloadUrl, version)
                    }
                    setNegativeButton(R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                }.create()
                if (isActivityAlive()) {
                    alertDialog.show()
                }
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun downloadApk(
        dialog: DialogInterface?,
        id: Int,
        downloadUrl: String,
        version: String
    ) {
        val uri = GITHUB_README_URL.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        activity.startActivity(intent)
        dialog?.dismiss()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun gotoGithub(dialog: DialogInterface, id: Int) {
        val uri = GITHUB_RELEASE_URL.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        activity.startActivity(intent)
        dialog.dismiss()
    }

//    private fun installApp(apkFile: File) {
//        if (ContextCompat.checkSelfPermission(
//                activity,
//                Manifest.permission.INSTALL_PACKAGES
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(
//                activity,
//                arrayOf(Manifest.permission.INSTALL_PACKAGES),
//                INSTALL_PERMISSION_CODE
//            )
//            return
//        }
//
//        if (apkFile.exists()) {
//            val intent = Intent(Intent.ACTION_VIEW)
//            intent.setDataAndType(
//                Uri.fromFile(apkFile),
//                "application/vnd.android.package-archive"
//            )
//            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//            activity.startActivity(intent)
//        }
//    }

//    private fun installApp(c: Cursor) {
//        val path: String =
//            c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
//        val apkFile = File(path)
//
//        if (ContextCompat.checkSelfPermission(
//                activity,
//                Manifest.permission.INSTALL_PACKAGES
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(
//                activity,
//                arrayOf(Manifest.permission.INSTALL_PACKAGES),
//                INSTALL_PERMISSION_CODE
//            )
//            return
//        }
//
//        if (apkFile.exists()) {
//            val intent = Intent(Intent.ACTION_VIEW)
//            intent.setDataAndType(
//                Uri.fromFile(apkFile),
//                "application/vnd.android.package-archive"
//            )
//            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//            activity.startActivity(intent)
//        }
//    }

}