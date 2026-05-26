package com.hippo.ehviewer.updater

import android.util.Log
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.locks.ReentrantLock

/**
 * GitHub Releases API adapter. Pure data layer — returns a sealed [UpdateResult]
 * describing what the caller should do; never touches Activity, Toast, Dialog, or
 * R.string. UI rendering is the caller's responsibility.
 *
 * Two semantic modes via [update]'s `manualChecking` flag:
 *
 * - **manual** (true): bypasses the 1-day throttle and skip-version filter — the
 *   user explicitly asked for a check, surface whatever we get back.
 * - **auto** (false): honors both throttle and skip-version, returning [Skipped]
 *   in either case so the caller can silently no-op.
 *
 * On any successful network round-trip (newer or up-to-date), the 1-day throttle
 * timestamp is advanced. Failed checks (network error) leave the cooldown intact
 * so the next attempt isn't pushed out by a transient failure.
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
     * Sealed result. Callers `when`-branch this to drive UI:
     *
     * - [NewerAvailable] — show update dialog / Snackbar with [release].
     * - [UpToDate] — show "no updates" toast (manual) or silent (auto).
     * - [NetworkError] — show "check failed" dialog (manual) or silent (auto).
     * - [Skipped] — already throttled / skip-versioned / lock-busy; no-op.
     */
    sealed class UpdateResult {
        data class NewerAvailable(val release: GhRelease) : UpdateResult()
        object UpToDate : UpdateResult()
        object NetworkError : UpdateResult()
        object Skipped : UpdateResult()
    }

    /**
     * Query the GitHub Releases API and classify the result. See class KDoc for
     * the [manualChecking] semantics. Suspends until network round-trip completes
     * or the lock is busy.
     */
    suspend fun update(manualChecking: Boolean): UpdateResult = withContext(Dispatchers.IO) {
        if (!manualChecking && !UpdateSettings.getIsUpdateTime()) {
            return@withContext UpdateResult.Skipped
        }

        if (!lock.tryLock()) {
            return@withContext UpdateResult.Skipped
        }
        try {
            val release = try {
                fetchLatestRelease(ServiceRegistry.networkModule.okHttpClient)
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
                Analytics.recordException(t)
                return@withContext UpdateResult.NetworkError
            }

            if (release == null) {
                return@withContext UpdateResult.NetworkError
            }

            // Successful network round-trip — advance the 1-day throttle regardless
            // of whether we found a newer release or not. Failed checks (NetworkError
            // above) intentionally leave the cooldown intact.
            UpdateSettings.putUpdateTime(System.currentTimeMillis())

            if (!isNewer(release)) {
                return@withContext UpdateResult.UpToDate
            }

            // Auto mode honors skip-version; manual mode bypasses (user explicitly asked).
            if (!manualChecking && release.versionCode == UpdateSettings.getSkipUpdateVersion()) {
                return@withContext UpdateResult.Skipped
            }

            UpdateResult.NewerAvailable(release)
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
