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

package com.hippo.ehviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Debug
import android.os.Process
import android.os.StrictMode
import android.os.Trace
import android.util.Log
import com.hippo.content.ContextLocalWrapper
import com.hippo.content.RecordingApplication
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.module.AppModule
import kotlinx.coroutines.launch
import com.lanraragi.reader.client.api.LRRClientProvider
import com.hippo.ehviewer.settings.AppLockGate
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.PrivacySettings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.lib.image.Image
import android.os.Handler
import android.os.Looper
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import com.hippo.util.BitmapUtils
import com.hippo.util.ExceptionUtils
import com.hippo.util.ReadableTime
import java.io.File
import java.util.Locale

class EhApplication : RecordingApplication() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mActivityList = mutableListOf<Activity>()

    private var initialized = false

    override fun attachBaseContext(base: Context) {
        // Apply locale before super.attachBaseContext() so the Application context
        // uses the correct language. Settings is not yet initialized here, so read
        // the preference directly from SharedPreferences and resolve it via the
        // shared AppearanceSettings.resolveAppLocale (same logic EhActivity uses).
        var locale: Locale? = null
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(base)
            locale = AppearanceSettings.resolveAppLocale(
                prefs.getString(AppearanceSettings.KEY_APP_LANGUAGE, "system")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read language preference, using system locale", e)
        }

        if (locale == null) {
            locale = Resources.getSystem().configuration.locale
        }
        // MUST stay a raw ContextImpl (no ContextWrapper shell):
        // ActivityThread.handleReceiver casts getBaseContext() to ContextImpl,
        // so a wrapped Application base crashes every manifest receiver —
        // caught live by the continue-reading AppWidgetProvider smoke test.
        super.attachBaseContext(ContextLocalWrapper.localeApplicationBase(base, locale))
    }

    @SuppressLint("StaticFieldLeak") // Safe: Application instance is process-scoped
    override fun onCreate() {
        instance = this

        // Touch AppModule.bootScope so that bootCEH/bootScope/activeProfileIdDeferred
        // are constructed before any other initialization that might want to launch
        // boot-time work. ServiceRegistry is not yet initialized at this point — any
        // coroutines launched from onCreate() before that point must use bootScope.
        AppModule.bootScope

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                // Always save crash file if onCreate() is not done
                if (!initialized || PrivacySettings.getSaveCrashLog()) {
                    Crash.saveCrashLog(instance, e)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to save crash log", e)
            }

            handler?.uncaughtException(t, e)
        }

        super.onCreate()

        cancelStaleRestartAlarm()

        // Debug-only StrictMode: penaltyLog surfaces main-thread disk/network
        // work and leaked closables during development (this class of bug —
        // e.g. a Room-flow JSON decode landing on main — is otherwise
        // invisible until it janks). Never enabled in release; log-only so
        // the known deliberate boot reads (locale prefs in attachBaseContext,
        // Settings warm-up below) show up without killing anything.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    // The detector GalleryActivity's dead block always meant
                    // to install (it called this after build()).
                    .detectFileUriExposure()
                    .penaltyLog()
                    .build()
            )
        }

        // INF-9: LRRAuthManager.initialize() spends 50-200ms on KeyStore binder
        // calls + encrypted-pref decryption. Run it on bootScope so it overlaps
        // the remaining main-thread initializers and the Activity launch window;
        // LRRAuthManager's readiness gate holds any reader that arrives early.
        val authInitJob = LRRAuthManager.scheduleInitialize(this, AppModule.bootScope)

        // Wrap each main-thread initialiser in a Trace section so cold-start
        // profiles in Android Studio Profiler / perfetto show exactly which
        // step dominates EhApplication.onCreate. Tracing is built into the OS
        // and is essentially free at runtime — kept enabled for release too
        // so production cold-start traces are not a black box.
        trace("EhApp.GetText.init") { GetText.initialize(this) }
        trace("EhApp.StatusCodeException.init") {
            com.hippo.network.StatusCodeException.initialize(this)
        }
        trace("EhApp.Settings.init") { Settings.initialize(this) }
        trace("EhApp.ReadableTime.init") { ReadableTime.initialize(this) }
        trace("EhApp.AppConfig.init") { AppConfig.initialize(this) }
        // Skip SpiderDen disk cache in LRR mode — it's EH-specific and wastes 40-640MB
        // SpiderDen.initialize(this);
        trace("EhApp.EhDB.init") { EhDB.initialize(this) }

        // Load active server profile into LRRAuthManager asynchronously, and verify
        // every profile has its API key still encrypted in storage. If any profile
        // lost its key (KeyStore down or partial corruption) we flag reauth so the
        // MainActivity dialog directs the user to re-enter credentials.
        // ServiceRegistry is not yet initialized, so use AppModule.bootScope which
        // is supervised and routes uncaught exceptions through bootCEH.
        AppModule.bootScope.launch {
            var resolvedId: Long? = null
            try {
                // INF-9: the calls below read+write encrypted prefs - wait for the
                // async initialize. Inside the try so the finally still completes
                // activeProfileIdDeferred if join() ever throws.
                authInitJob.join()
                val allProfiles = com.hippo.ehviewer.dao.AppDatabase.getInstance(this@EhApplication)
                    .miscDao().getAllServerProfiles()
                LRRAuthManager.markReauthIfProfilesUnprotected(allProfiles.map { it.id })
                val activeProfile = allProfiles.firstOrNull { it.isActive }
                if (activeProfile != null) {
                    LRRAuthManager.setActiveProfileId(activeProfile.id)
                    resolvedId = activeProfile.id
                }
            } catch (e: com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException) {
                Log.w(TAG, "KeyStore unavailable during profile load", e)
                // KeyStore unavailable — markReauthIfProfilesUnprotected already flagged
                // it (or initialize() did), and MainActivity will surface the dialog.
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load server profiles during init", e)
                // Surface non-KeyStore failures (DB corruption, migration error,
                // generic loader exception) to the first UI that can show a
                // dialog. Without this the failure was completely silent — user
                // saw the app come up as if no profiles existed, with no way
                // to distinguish "fresh install" from "DB blew up".
                AppModule.bootProfileLoadError.set(e)
                Analytics.recordException(e)
            } finally {
                // Always complete the deferred so awaiters never hang, even on the
                // failure path. complete() is a no-op if already completed.
                AppModule.activeProfileIdDeferred.complete(resolvedId)
            }
        }

        // Legacy migration — runs once on first launch after upgrading from old DB format.
        // ServiceRegistry is not yet initialized, so use AppModule.bootScope.
        AppModule.bootScope.launch {
            if (EhDB.needMerge()) {
                EhDB.mergeOldDB(this@EhApplication)
            }
        }


        // Legacy search-history import — one-time lift of the retired
        // SearchDatabase file into the per-profile Room store (issue #12).
        // Waits for the resolved active profile; without one (fresh install
        // mid-onboarding) the file stays put and the import retries next boot.
        AppModule.bootScope.launch {
            try {
                val profileId = AppModule.activeProfileIdDeferred.await()
                if (profileId != null) {
                    com.hippo.ehviewer.dao.LegacySearchHistoryImporter.importIfPresent(
                        this@EhApplication,
                        com.hippo.ehviewer.dao.AppDatabase.getInstance(this@EhApplication),
                        profileId,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "legacy search-history import launch failed")
            }
        }

        trace("EhApp.LRRClientProvider.init") { LRRClientProvider.init(this) }

        // Continue-reading shortcut publisher rides the reading-session-end
        // seam (issue #15). Registration is cheap; work happens per session end.
        com.hippo.ehviewer.ui.ContinueReadingShortcut.install()

        // Continue-reading home-screen widget rides the same seam (issue #9);
        // per-event work no-ops unless a widget instance is actually placed.
        com.hippo.ehviewer.appwidget.ContinueReadingWidget.install()

        // Daily reading aggregate rides the same seam (issue #20): one row per
        // (day x profile), accumulating history for future trend features.
        com.hippo.ehviewer.stats.DailyReadingAggregateRecorder.install()

        // Baseline launcher shortcuts, dynamically registered so they exist on
        // the .debug application id too (INF-10, issue #17). Binder calls —
        // keep off the cold-start main thread.
        AppModule.bootScope.launch {
            try {
                com.hippo.ehviewer.shortcuts.AppShortcuts.registerBaseline(this@EhApplication)
            } catch (e: Exception) {
                Log.w(TAG, "baseline shortcut registration failed")
            }
        }

        // Initialize ServiceRegistry (must be after Settings/EhDB)
        trace("EhApp.ServiceRegistry.init") { ServiceRegistry.initialize(this) }
        // Eagerly start network monitoring so isAvailable() is ready before first API call
        ServiceRegistry.networkModule.networkMonitor
        // Eagerly start the profile snapshot collector. Interceptors and the
        // download worker need a populated snapshot before their first read;
        // touching the lazy here kicks off the Room flow on app scope.
        ServiceRegistry.dataModule.profileLookupCache

        // One-shot v25→v26 backfill: any download row whose
        // DOWNLOAD_ROOT_URI is still NULL pre-dates the schema bump,
        // so adopt the user's current download-location URI as its
        // persistent root. The DAO UPDATE is gated on the column being
        // NULL — subsequent boots are no-ops, even after the user
        // changes the location again.
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val rootUri = DownloadSettings.getCurrentDownloadRootUri()
                if (!rootUri.isNullOrEmpty()) {
                    ServiceRegistry.dataModule.downloadDbRepository
                        .backfillDownloadRootUri(rootUri)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Download root URI backfill failed; will retry next boot", t)
            }
        }

        // Defer heavy JNI/native initialization to background thread.
        // These are not needed until the user actually opens a gallery or downloads.
        // libehviewer.so needs no eager load: its only consumer (GifHandler)
        // loads it in its own static initializer on first use.
        ServiceRegistry.coroutineModule.ioScope.launch {
            BitmapUtils.initialize(this@EhApplication)
            Image.initialize(this@EhApplication)
        }

        // One-time http_cache purge (audit #29): thumbnails used to be
        // disk-cached twice — Conaco's BeerBelly cache AND the shared OkHttp
        // http_cache. Conaco now fetches through a cache-less client, but
        // installs upgraded from older versions still carry up to 200 MB of
        // duplicate thumbnail bodies that nothing would ever evict (the cache
        // only trims on writes, and thumbnails were its only writer). A pref
        // guard makes later boots zero-cost. Runs after ServiceRegistry init
        // so the module's own Cache instance does the eviction (never open a
        // second Cache over the same directory).
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val prefs = getSharedPreferences("boot_cleanup", MODE_PRIVATE)
                if (!prefs.getBoolean("http_cache_thumb_purge_done", false)) {
                    ServiceRegistry.networkModule.cache.evictAll()
                    prefs.edit { putBoolean("http_cache_thumb_purge_done", true) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "http_cache one-time purge failed")
            }
        }

        if (PrivacySettings.getEnableAnalytics()) {
            Analytics.start(this)
        }

        // Re-prompt the security pattern whenever the whole app returns from
        // background. ProcessLifecycleOwner debounces by ~700 ms, so config
        // changes (rotation), the notification shade, BiometricPrompt, and
        // other transient overlays do NOT fire ON_STOP and therefore do not
        // trigger a spurious lock. EhActivity / MainActivity consume the flag
        // from onResume and gate on hasPattern() there — keeping the
        // EncryptedSharedPreferences read off the lifecycle hot path.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLockGate.onAppBackgrounded()
            }
        })

        // Do io tasks in background thread
        ServiceRegistry.coroutineModule.ioScope.launch {
            // Check no media file
            try {
                val downloadLocation = DownloadSettings.getDownloadLocation()
                if (DownloadSettings.getMediaScan()) {
                    CommonOperations.removeNoMediaFile(downloadLocation)
                } else {
                    CommonOperations.ensureNoMediaFile(downloadLocation)
                }
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
            }

            // Clear temp files
            try {
                clearTempDir()
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
            }

            try {
                AppConfig.deleteOldParseErrorFiles()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete old parse error files", e)
            }

            // Migrate downloads from old app-private path to user-visible location.
            // Skip if a previous run already completed migration successfully.
            if (!Settings.getBoolean(KEY_DOWNLOAD_MIGRATION_DONE, false)) {
                try {
                    val oldBase = File(getExternalFilesDir(null), "download")
                    if (oldBase.exists() && oldBase.isDirectory) {
                        val newBase = DownloadSettings.getDownloadLocation()
                        val path = newBase?.uri?.path
                        if (newBase != null && "file" == newBase.uri.scheme && path != null) {
                            val newBaseFile = File(path)
                            val children = oldBase.listFiles()
                            if (children != null) {
                                for (child in children) {
                                    if (child.isDirectory) {
                                        val dest = File(newBaseFile, child.name)
                                        if (!dest.exists()) {
                                            if (child.renameTo(dest)) {
                                                Log.i(TAG, "Migrated download dir: " + child.name)
                                            }
                                        }
                                    }
                                }
                            }
                            val remaining = oldBase.list()
                            if (remaining == null || remaining.isEmpty()) {
                                oldBase.delete()
                                Settings.putBoolean(KEY_DOWNLOAD_MIGRATION_DONE, true)
                            }
                            // If remaining dirs exist, do NOT set flag — retry next startup
                        }
                    } else {
                        // oldBase doesn't exist (fresh install or already cleaned up)
                        Settings.putBoolean(KEY_DOWNLOAD_MIGRATION_DONE, true)
                    }
                } catch (t: Exception) {
                    Log.w(TAG, "Download directory migration failed", t)
                }
            }
        }

        // Check app update
        update()

        // Update version code
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            Settings.putVersionCode(pi.versionCode)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Retrieve package info for version code", e)
        }

        if (DEBUG_PRINT_NATIVE_MEMORY || DEBUG_PRINT_IMAGE_COUNT) {
            debugPrint()
        }

        initialized = true
    }

    /**
     * Persist the current package's versionCode so future first-launch hooks
     * can detect upgrades. The historical EhViewer code keyed first-launch
     * tutorials off `version < 52` here; that branch was unreachable under
     * LR Reader's `MAJOR*10000 + MINOR*100 + PATCH` scheme (smallest
     * value 10000) so it was removed. If a real first-launch hook is needed
     * later, gate it on `Settings.getVersionCode() == 0` (fresh install).
     */
    private fun update() {
        // Intentionally empty — kept as the documented integration point for
        // future versionCode-driven upgrades.
    }

    private fun clearTempDir() {
        var dir = AppConfig.getTempDir()
        if (dir != null) {
            FileUtils.deleteContent(dir)
        }
        dir = AppConfig.getExternalTempDir()
        if (dir != null) {
            FileUtils.deleteContent(dir)
        }
        // Add .nomedia to external temp dir
        CommonOperations.ensureNoMediaFile(UniFile.fromFile(AppConfig.getExternalTempDir()))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ServiceRegistry.clientModule.clearMemoryCache()
            ServiceRegistry.dataModule.clearArchiveDetailCache()
        }
    }

    private fun debugPrint() {
        object : Runnable {
            override fun run() {
                if (DEBUG_PRINT_NATIVE_MEMORY) {
                    Log.i(
                        TAG, "Native memory: " + FileUtils.humanReadableByteCount(
                            Debug.getNativeHeapAllocatedSize(), false
                        )
                    )
                }
                mainHandler.postDelayed(this, DEBUG_PRINT_INTERVAL)
            }
        }.run()
    }

    // ======== Activity registry ========

    fun registerActivity(activity: Activity) {
        mActivityList.add(activity)
    }

    fun unregisterActivity(activity: Activity) {
        mActivityList.remove(activity)
    }

    val topActivity: Activity?
        get() = mActivityList.lastOrNull()

    // ======== Service override for device compatibility ========

    override fun startService(service: Intent): ComponentName? {
        return try {
            super.startService(service)
        } catch (t: Throwable) {
            ExceptionUtils.throwIfFatal(t)
            null
        }
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        return try {
            super.bindService(service, conn, flags)
        } catch (t: Throwable) {
            ExceptionUtils.throwIfFatal(t)
            false
        }
    }

    override fun unbindService(conn: ServiceConnection) {
        try {
            super.unbindService(conn)
        } catch (t: Throwable) {
            ExceptionUtils.throwIfFatal(t)
        }
    }

    // ======== Memory cache (instance method called by AdvancedFragment) ========

    fun clearMemoryCache() {
        ServiceRegistry.clientModule.clearMemoryCache()
        ServiceRegistry.dataModule.clearArchiveDetailCache()
    }

    /**
     * Restart the whole app process ("rebirth"). Schedules a one-shot inexact
     * alarm to relaunch the launcher activity ~100 ms after this process is
     * killed; standard ProcessPhoenix-style pattern, no extra permission needed
     * (`AlarmManager.set` with `RTC` is inexact and exempt from
     * SCHEDULE_EXACT_ALARM on API 31+). Uses `getLaunchIntentForPackage` so the
     * relaunched task starts at whichever Activity is the registered launcher.
     *
     * Unlike [recreate] (which only re-creates the live activities), this rebuilds
     * everything bound to the application context at process start — the wrapped
     * locale, GetText's cached resources, notification/service strings — because
     * the fresh process re-runs [attachBaseContext]. Use it for changes an
     * activity recreate cannot pick up, e.g. an in-app language switch.
     *
     * Callers finishing their own task first (e.g. `finishAffinity()`) should do
     * so before calling this; this method itself only schedules the relaunch and
     * kills the process.
     */
    fun restart() {
        restartPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?.let { pendingIntent ->
                val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent)
            }
        Process.killProcess(Process.myPid())
    }

    /**
     * The relaunch PendingIntent used by [restart]. Reconstructed with the same
     * request code (and filter-equal Intent) by [cancelStaleRestartAlarm] so a
     * pending copy can be found and cancelled on the next process start.
     */
    private fun restartPendingIntent(flags: Int): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.also {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return null
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    /**
     * The rebirth alarm scheduled by [restart] races the OS's own relaunch of a
     * killed foreground task: the system restarts the top activity almost
     * immediately, while the inexact alarm can land seconds later and replace
     * the activity the user is already interacting with (open drawer snapping
     * shut, list state lost — observed ~5s late on API 35). Whichever path
     * booted this process, a still-pending alarm is stale by definition.
     */
    private fun cancelStaleRestartAlarm() {
        restartPendingIntent(PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { pendingIntent ->
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
                pendingIntent.cancel()
            }
    }

    // --- GlobalStuff delegation ---

    fun putGlobalStuff(o: Any): Int {
        return ServiceRegistry.appModule.putGlobalStuff(o)
    }

    fun containGlobalStuff(id: Int): Boolean {
        return ServiceRegistry.appModule.containGlobalStuff(id)
    }

    fun getGlobalStuff(id: Int): Any? {
        return ServiceRegistry.appModule.getGlobalStuff(id)
    }

    fun removeGlobalStuff(id: Int): Any? {
        return ServiceRegistry.appModule.removeGlobalStuff(id)
    }

    fun removeGlobalStuff(o: Any) {
        ServiceRegistry.appModule.removeGlobalStuff(o)
    }

    // --- TempCache delegation ---

    fun putTempCache(key: String, o: Any): String {
        return ServiceRegistry.appModule.putTempCache(key, o)
    }

    fun containTempCache(key: String): Boolean {
        return ServiceRegistry.appModule.containTempCache(key)
    }

    fun getTempCache(key: String): Any? {
        return ServiceRegistry.appModule.getTempCache(key)
    }

    fun removeTempCache(key: String): Any? {
        return ServiceRegistry.appModule.removeTempCache(key)
    }

    /**
     * Wrap [block] in a [Trace] section so cold-start profiles attribute time
     * to a named slice.
     *
     * Trace is enabled in release as well: `Trace.beginSection` is a thin
     * native call (~microseconds) and the section name is a compile-time
     * `const String`, so no per-call allocation. The win is that
     * **production cold-start profiles in Android Studio Profiler / perfetto
     * also see the named slices**, which is what the basline P2 C6 audit
     * recommendation called for. Restricting to `BuildConfig.DEBUG` would
     * have left release builds as a black box for boot-time analysis.
     */
    private inline fun trace(name: String, block: () -> Unit) {
        Trace.beginSection(name)
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    companion object {
        private val TAG = EhApplication::class.java.simpleName

        const val BETA: Boolean = false

        private const val KEY_DOWNLOAD_MIGRATION_DONE = "download_migration_v1_done"

        private const val DEBUG_PRINT_NATIVE_MEMORY = false
        private const val DEBUG_PRINT_IMAGE_COUNT = false
        private const val DEBUG_PRINT_INTERVAL = 3000L

        @JvmStatic
        lateinit var instance: EhApplication
            private set
    }
}
