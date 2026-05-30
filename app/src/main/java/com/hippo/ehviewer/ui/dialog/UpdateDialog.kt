package com.hippo.ehviewer.ui.dialog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hippo.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.updater.ApkDownloader
import com.hippo.ehviewer.updater.DownloadProgress
import com.hippo.ehviewer.updater.GhRelease
import kotlinx.coroutines.launch
import java.io.File


object UpdateDialog {
    const val GITHUB_RELEASE_URL = "https://github.com/Xslx98/LRReader/releases"
    const val GITHUB_README_URL =
        "https://github.com/Xslx98/LRReader/blob/main/README.md"

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
                    .setPositiveButton(R.string.yes) { dialog, _ ->
                        dialog.dismiss()
                        openReleaseInBrowser(activity, GITHUB_RELEASE_URL)
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
            val title = if (release.name.isNotBlank()) release.name else release.tagName

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
                    setPositiveButton(R.string.update) { dialog, _ ->
                        dialog.dismiss()
                        startDownload(activity, release)
                    }
                    setNeutralButton(R.string.update_ignore) { dialog, _ ->
                        // Persist this version as skipped. Auto cold-start checks honor
                        // skip-version (→ silent Skipped); manual checks bypass it, so the
                        // user can re-surface the update from About → Check for updates.
                        UpdateSettings.putSkipUpdateVersion(release.versionCode)
                        dialog.dismiss()
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

    // ── New methods (real download + install flow) ─────────────────────────

    /**
     * Inflate the in-dialog progress view, kick off [ApkDownloader.download], and route the
     * Flow's [DownloadProgress] events to UI updates + install intent. Cancellation via the
     * dialog's "Cancel" button → `dialog.cancel()` → `onCancel` → `job.cancel()` → ApkDownloader
     * awaitClose deletes the partial file.
     */
    private fun startDownload(activity: Activity, release: GhRelease) {
        if (!isActivityAlive(activity)) return
        val dest = ApkDownloader.targetFile(activity, release)
        val url = release.apkAsset?.browserDownloadUrl
        if (dest == null || url.isNullOrBlank()) {
            showDownloadFailedDialog(activity, release)
            return
        }
        val scope = (activity as? AppCompatActivity)?.lifecycleScope ?: run {
            Analytics.recordException(IllegalStateException("UpdateDialog requires AppCompatActivity for download"))
            return
        }

        val progressView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_progress, null, false)
        val titleView = progressView.findViewById<TextView>(R.id.update_progress_title)
        val barView = progressView.findViewById<LinearProgressIndicator>(R.id.update_progress_bar)
        val percentView = progressView.findViewById<TextView>(R.id.update_progress_percent)
        titleView.text = activity.getString(R.string.update_downloading_title, release.tagName)
        barView.progress = 0
        percentView.text = "0%"

        val progressDialog = AlertDialog.Builder(activity)
            .setView(progressView)
            .setNegativeButton(R.string.cancel) { d, _ -> d.cancel() }
            .setCancelable(true)
            .create()

        val job = scope.launch {
            ApkDownloader.download(url, dest).collect { progress ->
                when (progress) {
                    is DownloadProgress.InProgress -> {
                        val pct = progress.percent.coerceIn(0, 100)
                        barView.progress = pct
                        percentView.text = "$pct%"
                    }
                    DownloadProgress.Success -> {
                        progressDialog.dismiss()
                        installApp(activity, dest)
                    }
                    is DownloadProgress.Failed -> {
                        Analytics.recordException(progress.cause)
                        progressDialog.dismiss()
                        showDownloadFailedDialog(activity, release)
                    }
                }
            }
        }
        progressDialog.setOnCancelListener { job.cancel() }
        progressDialog.show()
    }

    /**
     * Fire system PackageInstaller intent. Gated on canRequestPackageInstalls; if false,
     * routes to [showInstallPermissionDialog] which jumps the user to system settings and
     * registers an observer to auto-retry on return.
     */
    private fun installApp(activity: Activity, apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialog(activity, apkFile)
            return
        }
        val authority = "${activity.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(activity, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Analytics.recordException(e)
            openReleaseInBrowser(activity, GITHUB_RELEASE_URL)
        }
    }

    private fun showInstallPermissionDialog(activity: Activity, apkFile: File) {
        if (!isActivityAlive(activity)) return
        AlertDialog.Builder(activity)
            .setMessage(R.string.update_install_permission_needed)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    activity.startActivity(intent)
                    observePermissionGranted(activity, apkFile)
                } catch (e: ActivityNotFoundException) {
                    Analytics.recordException(e)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Register a one-shot LifecycleObserver to auto-retry install when the user returns
     * from ACTION_MANAGE_UNKNOWN_APP_SOURCES. Two-phase trigger (onStop then onResume)
     * avoids firing on initial state sync-up when addObserver runs while RESUMED.
     *
     * Edge cases:
     * - User toggles permission ON, returns: installApp fires, system installer appears
     * - User opens settings, doesn't toggle, returns: silent no-op
     * - APK evicted from cacheDir while user was in settings: silent no-op
     * - Activity destroyed (rotation, etc.): observer dies with the LifecycleOwner;
     *   user must re-trigger Check for Updates (acceptable edge)
     * - Process killed from settings: observer + cacheDir likely both lost; user re-triggers
     */
    private fun observePermissionGranted(activity: Activity, apkFile: File) {
        val lifecycleOwner = activity as? LifecycleOwner ?: run {
            Analytics.recordException(IllegalStateException("Activity is not LifecycleOwner"))
            return
        }
        val observer = object : DefaultLifecycleObserver {
            private var leftForeground = false
            override fun onStop(owner: LifecycleOwner) {
                leftForeground = true
            }
            override fun onResume(owner: LifecycleOwner) {
                if (!leftForeground) return
                owner.lifecycle.removeObserver(this)
                if (apkFile.exists() && activity.packageManager.canRequestPackageInstalls()) {
                    installApp(activity, apkFile)
                }
                // else: permission still not granted, or APK evicted — silent
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    private fun showDownloadFailedDialog(activity: Activity, release: GhRelease) {
        if (!isActivityAlive(activity)) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_fail)
            .setMessage(R.string.update_download_failed)
            .setPositiveButton(R.string.update_open_in_browser) { _, _ ->
                openReleaseInBrowser(activity, release.htmlUrl.ifBlank { GITHUB_RELEASE_URL })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun openReleaseInBrowser(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Analytics.recordException(e)
        }
    }
}
