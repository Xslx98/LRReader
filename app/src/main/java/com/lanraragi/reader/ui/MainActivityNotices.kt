package com.lanraragi.reader.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.lanraragi.framework.network.Network
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.download.DownloadResumeBanner
import com.lanraragi.reader.download.DownloadService
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankTagSyncFailedEvent
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.reader.settings.UpdateSettings
import com.lanraragi.reader.ui.dialog.UpdateDialog
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import com.lanraragi.reader.updater.AppUpdater
import com.lanraragi.reader.updater.GhRelease
import com.lanraragi.reader.updater.UpdateAdvisories
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The snackbars and dialogs MainActivity raises on its own: the download
 * resume banner, tank tag-sync failures, the update and advisory notices,
 * and the start-up download-location and cellular checks. [host] is the
 * view snackbars attach to (the drawer layout), null once it is gone.
 */
class MainActivityNotices(
    private val activity: MainActivity,
    private val host: () -> View?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Waits for the download list before showing the resume banner; one per start. */
    private var resumeBannerJob: Job? = null

    fun onStart() {
        resumeBannerJob?.cancel()
        resumeBannerJob = activity.lifecycleScope.launch {
            // At cold start the download list loads in the background and records
            // downloads a killed process left queued (A47) as it goes; wait for it
            // so they are offered now rather than on the next foreground. Past the
            // timeout, show whatever is already recorded.
            withTimeoutOrNull(DOWNLOADS_INIT_WAIT_MS) {
                withContext(ioDispatcher) {
                    ServiceRegistry.dataModule.downloadManager.awaitInitAsync(timeoutMs = Long.MAX_VALUE)
                }
            }
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                maybeShowDownloadResumeBanner()
            }
        }
    }

    fun onStop() {
        // A waiter from this start must not consume the banner for the next one:
        // two waiters would each consume, the second Snackbar replacing the first.
        resumeBannerJob?.cancel()
        resumeBannerJob = null
    }

    /**
     * Tank tag materialization failures (spec 2026-09-22 §5.3) are
     * best-effort follow-ups of a membership write that already succeeded;
     * whichever scene is up, the shell names the tank in a Snackbar. Queued
     * tag retries and local download-group write failures (REL-24) use the
     * same channel with a message that names no tank.
     */
    fun observeTankTagSyncFailures() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppEventBus.tankTagSyncFailedEvent.collect { event ->
                    val host = host() ?: return@collect
                    val text = when (event.kind) {
                        TankTagSyncFailedEvent.Kind.TAGS ->
                            activity.getString(R.string.tank_tag_sync_failed, event.tankName)
                        TankTagSyncFailedEvent.Kind.CATEGORIES ->
                            activity.getString(R.string.tank_category_sync_failed, event.tankName)
                        TankTagSyncFailedEvent.Kind.TAGS_PENDING ->
                            activity.getString(R.string.tank_tag_sync_pending)
                        TankTagSyncFailedEvent.Kind.DOWNLOAD_GROUP ->
                            activity.getString(R.string.tank_download_group_failed)
                    }
                    Snackbar.make(host, text, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * On return to the foreground, surface any downloads that paused waiting for
     * the network or gave up after the wait timeout. One-shot (consume()).
     */
    private fun maybeShowDownloadResumeBanner() {
        // Note: if app-lock is active, this fires in onStart() before SecurityScene
        // is pushed in onResume(), so the Snackbar appears briefly behind the lock and
        // its consumed state will not re-show after unlock. Accepted v1 limitation.
        val host = host() ?: return
        val resources = activity.resources
        when (val snapshot = DownloadResumeBanner.consume()) {
            is DownloadResumeBanner.Snapshot.Paused -> {
                Snackbar.make(
                    host,
                    activity.getString(R.string.download_resume_paused_snackbar),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.download_resume_view_action) {
                    activity.startScene(Announcer(DownloadsScene::class.java))
                }.show()
            }
            is DownloadResumeBanner.Snapshot.StorageFull -> showRequeueSnackbar(
                host,
                resources.getQuantityString(
                    R.plurals.download_resume_storage_full_snackbar, snapshot.count, snapshot.count
                ),
                R.string.download_resume_resume_action,
                snapshot.arcids,
            )
            is DownloadResumeBanner.Snapshot.SystemLimited -> showRequeueSnackbar(
                host,
                resources.getQuantityString(
                    R.plurals.download_resume_system_limit_snackbar, snapshot.count, snapshot.count
                ),
                R.string.download_resume_resume_action,
                snapshot.arcids,
            )
            is DownloadResumeBanner.Snapshot.TimedOut -> showRequeueSnackbar(
                host,
                resources.getQuantityString(
                    R.plurals.download_resume_timed_out_snackbar, snapshot.count, snapshot.count
                ),
                R.string.download_resume_retry_action,
                snapshot.arcids,
            )
            is DownloadResumeBanner.Snapshot.Interrupted -> showRequeueSnackbar(
                host,
                resources.getQuantityString(
                    R.plurals.download_resume_interrupted_snackbar, snapshot.count, snapshot.count
                ),
                R.string.download_resume_resume_action,
                snapshot.arcids,
            )
            DownloadResumeBanner.Snapshot.None -> { /* nothing to show */ }
        }
    }

    /** A Snackbar whose action puts [arcids] back in the download queue. */
    private fun showRequeueSnackbar(host: View, message: String, actionRes: Int, arcids: List<String>) {
        Snackbar.make(host, message, Snackbar.LENGTH_LONG).setAction(actionRes) {
            val intent = Intent(activity, DownloadService::class.java)
                .setAction(DownloadService.ACTION_START_RANGE)
                .putStringArrayListExtra(DownloadService.KEY_ARCID_LIST, ArrayList(arcids))
            activity.startService(intent)
        }.show()
    }

    fun checkDownloadLocation() {
        // getDownloadLocation + ensureDir stat/mkdirs the download root — keep
        // that off the main thread's first-frame path.
        activity.lifecycleScope.launch {
            val valid = withContext(ioDispatcher) {
                val uniFile = DownloadSettings.getDownloadLocation()
                // null == uniFile for first start
                uniFile == null || uniFile.ensureDir()
            }
            if (valid || activity.isFinishing) return@launch
            AlertDialog.Builder(activity)
                .setTitle(R.string.waring)
                .setMessage(R.string.invalid_download_location)
                .setPositiveButton(R.string.get_it, null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    fun checkCellularNetwork() {
        if (Network.getActiveNetworkType(activity) == ConnectivityManager.TYPE_MOBILE) {
            activity.showTip(R.string.cellular_network_warning, BaseScene.LENGTH_SHORT)
        }
    }

    /**
     * Cold-start auto check. Honors the user toggle; all other guards
     * (1-day throttle, skip-version, versionCode comparison) are internal
     * to AppUpdater and surfaced via the sealed UpdateResult.
     *
     * Failure modes (network error, GitHub API non-2xx, unparseable tag) are
     * silent — user discovers via the next day's check or via manual entry.
     */
    fun maybeAutoCheckUpdates() {
        if (!UpdateSettings.getAutoCheckUpdates()) return

        activity.lifecycleScope.launch {
            val result = AppUpdater.update(manualChecking = false)
            if (result is AppUpdater.UpdateResult.NewerAvailable) {
                showUpdateSnackbar(result.release)
            }
            // UpToDate / NetworkError / Skipped — auto path is silent for all of these.
            // AppUpdater handles throttle, skip-version, and putUpdateTime internally.
            // Advisories share the update check's toggle and daily throttle:
            // Skipped means no network round trip today.
            if (result !is AppUpdater.UpdateResult.Skipped) maybeShowAdvisory()
        }
    }

    /**
     * Shows the first unseen advisory.json notice for this version, once
     * (audit C46): the only way to warn users of a bad build, since Android
     * cannot roll an app back.
     */
    private suspend fun maybeShowAdvisory() {
        val advisories = withContext(ioDispatcher) {
            UpdateAdvisories.fetch(ServiceRegistry.networkModule.okHttpClient)
        }
        val locale = activity.resources.configuration.locales[0]
        val advisory = UpdateAdvisories.pick(
            advisories, BuildConfig.VERSION_CODE, UpdateSettings.getSeenAdvisories(), locale
        )
        if (advisory != null && !activity.isFinishing && !activity.isDestroyed) {
            UpdateSettings.markAdvisorySeen(advisory.id)
            val builder = AlertDialog.Builder(activity)
                .setTitle(R.string.update_advisory_title)
                .setMessage(advisory.messageFor(locale))
                .setPositiveButton(android.R.string.ok, null)
            val url = advisory.safeUrl
            if (url != null) {
                builder.setNeutralButton(R.string.update_advisory_details) { _, _ -> openAdvisoryUrl(url) }
            }
            builder.show()
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun openAdvisoryUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser for the advisory link", e)
        }
    }

    private fun showUpdateSnackbar(release: GhRelease) {
        val host = host() ?: return
        val versionLabel = release.tagName
        Snackbar.make(
            host,
            activity.getString(R.string.update_snackbar_text, versionLabel),
            Snackbar.LENGTH_LONG,
        )
            .setAction(R.string.update_snackbar_action) {
                UpdateDialog.showUpdateDialog(activity, release)
            }
            .show()
    }

    private companion object {
        const val TAG = "MainActivity"

        /** How long onStart waits for the download list to load before showing the resume banner. */
        const val DOWNLOADS_INIT_WAIT_MS = 10_000L
    }
}
