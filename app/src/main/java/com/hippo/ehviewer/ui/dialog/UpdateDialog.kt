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


object UpdateDialog {
    const val GITHUB_RELEASE_URL = "https://github.com/Xslx98/LRReader/releases"
    const val GITHUB_README_URL =
        "https://github.com/Xslx98/LRReader/blob/main/README.md"
    const val INSTALL_PERMISSION_CODE = 1002

    private fun isActivityAlive(activity: Activity): Boolean {
        return !(activity.isFinishing || activity.isDestroyed)
    }

    fun showCheckFailDialog(activity: Activity) {
        try {
            ContextCompat.getMainExecutor(activity).execute {
                if (!isActivityAlive(activity)) {
                    return@execute
                }
                val alertDialog = AlertDialog.Builder(activity)
                    .setIcon(R.mipmap.ic_launcher)
                    .setTitle(R.string.update_fail)
                    .setMessage(R.string.update_fail_info)
                    .setPositiveButton(R.string.yes) { dialog, id ->
                        gotoGithub(activity, dialog, id)
                    }
                    .setNegativeButton(R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                if (isActivityAlive(activity)) {
                    alertDialog.show()
                }
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    fun showUpdateDialog(activity: Activity, release: GhRelease) {
        try {
            val version = release.tagName
            val title = if (release.name.isNotBlank()) release.name else release.tagName
            val downloadUrl = release.apkAsset?.browserDownloadUrl.orEmpty()

            ContextCompat.getMainExecutor(activity).execute {
                if (!isActivityAlive(activity)) {
                    return@execute
                }
                val alertDialog = AlertDialog.Builder(activity).apply {
                    setIcon(R.mipmap.ic_launcher)
                    setTitle(title)
                    if (release.body.isNotBlank()) {
                        setMessage(release.body)
                    }
                    setPositiveButton(R.string.update) { dialog, id ->
                        downloadApk(activity, dialog, id, downloadUrl, version)
                    }
                    setNegativeButton(R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                }.create()
                if (isActivityAlive(activity)) {
                    alertDialog.show()
                }
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun downloadApk(
        activity: Activity,
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
    private fun gotoGithub(activity: Activity, dialog: DialogInterface, id: Int) {
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
