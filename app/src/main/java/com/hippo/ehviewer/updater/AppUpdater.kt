package com.hippo.ehviewer.updater

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.ui.dialog.UpdateDialog
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Date
import java.util.concurrent.locks.ReentrantLock

/**
 * GitHub Releases API adapter. Two entry surfaces:
 *
 * - [update] — manual or auto-flow with UI feedback (toasts + dialog). Manual mode
 *   surfaces failures via [UpdateDialog.showCheckFailDialog]; auto mode is silent.
 *   Manual mode bypasses the 1-day throttle; auto mode respects it.
 *
 * - [checkInBackground] — pure suspend function returning [GhRelease] or null, no UI.
 *   Used by MainActivity to feed the cold-start Snackbar pathway.
 *
 * Network: single GET to api.github.com/repos/Xslx98/LRReader/releases/latest
 * (unauthenticated; 60 req/hr per IP — well below the 1-day throttle ceiling).
 *
 * No on-disk persistence — the previous self-hosted-JSON file dance was removed
 * along with the legacy UpdateInfo/UpdateContent EhViewer scaffold.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Xslx98/LRReader/releases/latest"

    private val updateJson = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantLock()

    /**
     * Background-suspend check. Used by [com.hippo.ehviewer.ui.MainActivity] auto path.
     * Returns null on network failure or non-2xx response (silent — caller decides
     * whether to surface).
     */
    suspend fun checkInBackground(): GhRelease? = withContext(Dispatchers.IO) {
        try {
            fetchLatestRelease(ServiceRegistry.networkModule.okHttpClient)
        } catch (t: Throwable) {
            ExceptionUtils.throwIfFatal(t)
            Log.w(TAG, "checkInBackground failed", t)
            null
        }
    }

    /**
     * Manual or auto entry point with UI feedback. Bypasses 1-day throttle when
     * [manualChecking] is true; respects it otherwise. Reentrant — `tryLock` so
     * simultaneous taps no-op.
     *
     * Suspends until the network call completes (or the lock is busy), so callers
     * can render busy state for the duration of the request.
     */
    suspend fun update(activity: Activity, manualChecking: Boolean) = withContext(Dispatchers.IO) {
        if (!manualChecking && !UpdateSettings.getIsUpdateTime()) {
            return@withContext
        }

        if (!lock.tryLock()) {
            return@withContext
        }
        try {
            val release = try {
                fetchLatestRelease(ServiceRegistry.networkModule.okHttpClient)
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
                Analytics.recordException(t)
                null
            }

            if (release == null) {
                if (manualChecking) {
                    ContextCompat.getMainExecutor(activity).execute {
                        UpdateDialog(activity).showCheckFailDialog()
                    }
                }
                return@withContext
            }

            if (!isNewer(release)) {
                if (manualChecking) {
                    ContextCompat.getMainExecutor(activity).execute {
                        Toast.makeText(
                            activity,
                            R.string.update_to_date,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                return@withContext
            }

            // Manual mode ignores skip-version (user explicitly asked); auto mode honors it.
            if (!manualChecking && release.versionCode == UpdateSettings.getSkipUpdateVersion()) {
                return@withContext
            }

            ContextCompat.getMainExecutor(activity).execute {
                UpdateDialog(activity).showUpdateDialog(release)
            }
            UpdateSettings.putUpdateTime(Date().time)
        } finally {
            lock.unlock()
        }
    }

    private fun fetchLatestRelease(client: OkHttpClient): GhRelease? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w(TAG, "GitHub Releases API non-2xx: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            return updateJson.decodeFromString<GhRelease>(body)
        }
    }

    private fun isNewer(release: GhRelease): Boolean {
        val remoteCode = release.versionCode
        if (remoteCode <= 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot parse versionCode from tag '${release.tagName}' / asset; skipping")
            return false
        }
        return remoteCode > BuildConfig.VERSION_CODE
    }
}
