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
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Debug
import android.util.Log
import com.hippo.Native
import com.hippo.a7zip.A7Zip
import com.hippo.content.ContextLocalWrapper
import com.hippo.content.RecordingApplication
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.module.AppModule
import kotlinx.coroutines.launch
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.settings.PrivacySettings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.unifile.UniFile
import com.hippo.util.BitmapUtils
import com.hippo.util.ExceptionUtils
import com.hippo.util.ReadableTime
import java.io.File
import java.util.Locale

class EhApplication : RecordingApplication() {

    private val mActivityList = mutableListOf<Activity>()

    private var initialized = false

    override fun attachBaseContext(base: Context) {
        // Apply locale before super.attachBaseContext() so the Application context
        // uses the correct language. Settings is not yet initialized here, so we
        // read the preference directly from SharedPreferences.
        var locale: Locale? = null
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(base)
            val language = prefs.getString("app_language", "system")
            if (language != null && language != "system") {
                val split = language.split("-")
                locale = when (split.size) {
                    1 -> Locale(split[0])
                    2 -> Locale(split[0], split[1])
                    3 -> Locale(split[0], split[1], split[2])
                    else -> null
                }
            }
        } catch (_: Exception) {
            // First launch or preference not yet available — use system locale
        }

        if (locale == null) {
            locale = Resources.getSystem().configuration.locale
        }
        super.attachBaseContext(ContextLocalWrapper.wrap(base, locale))
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
            } catch (_: Throwable) {
            }

            handler?.uncaughtException(t, e)
        }

        super.onCreate()

        GetText.initialize(this)
        com.hippo.network.StatusCodeException.initialize(this)
        Settings.initialize(this)
        LRRAuthManager.initialize(this)
        ReadableTime.initialize(this)
        AppConfig.initialize(this)
        // Skip SpiderDen disk cache in LRR mode — it's EH-specific and wastes 40-640MB
        // SpiderDen.initialize(this);
        EhDB.initialize(this)

        // Load active server profile into LRRAuthManager asynchronously, and verify
        // every profile has its API key still encrypted in storage. If any profile
        // lost its key (KeyStore down or partial corruption) we flag reauth so the
        // MainActivity dialog directs the user to re-enter credentials.
        // ServiceRegistry is not yet initialized, so use AppModule.bootScope which
        // is supervised and routes uncaught exceptions through bootCEH.
        AppModule.bootScope.launch {
            var resolvedId: Long? = null
            try {
                val allProfiles = EhDB.getAllServerProfilesAsync()
                LRRAuthManager.markReauthIfProfilesUnprotected(allProfiles.map { it.id })
                val activeProfile = allProfiles.firstOrNull { it.isActive }
                if (activeProfile != null) {
                    LRRAuthManager.setActiveProfileId(activeProfile.id)
                    resolvedId = activeProfile.id
                }
            } catch (_: com.hippo.ehviewer.client.lrr.LRRSecureStorageUnavailableException) {
                // KeyStore unavailable — markReauthIfProfilesUnprotected already flagged
                // it (or initialize() did), and MainActivity will surface the dialog.
            } catch (_: Exception) {
                // DB not ready yet on first launch — safe to ignore
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

        LRRClientProvider.init(this)

        // Initialize ServiceRegistry (must be after Settings/EhDB)
        ServiceRegistry.initialize(this)
        // Eagerly start network monitoring so isAvailable() is ready before first API call
        ServiceRegistry.networkModule.networkMonitor

        // Defer heavy JNI/native initialization to background thread.
        // These are not needed until the user actually opens a gallery or downloads.
        ServiceRegistry.coroutineModule.ioScope.launch {
            BitmapUtils.initialize(this@EhApplication)
            Image.initialize(this@EhApplication)
            Native.initialize()
            A7Zip.initialize(this@EhApplication)
        }

        if (PrivacySettings.getEnableAnalytics()) {
            Analytics.start(this)
        }

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
            } catch (_: Exception) {
            }

            // Migrate downloads from old app-private path to user-visible location.
            // Skip if a previous run already completed migration successfully.
            if (!Settings.getBoolean(KEY_DOWNLOAD_MIGRATION_DONE, false)) {
                try {
                    val oldBase = File(getExternalFilesDir(null), "download")
                    if (oldBase.exists() && oldBase.isDirectory) {
                        val newBase = DownloadSettings.getDownloadLocation()
                        if (newBase != null && "file" == newBase.uri.scheme) {
                            val newBaseFile = File(newBase.uri.path!!)
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
        } catch (_: PackageManager.NameNotFoundException) {
            // Ignore
        }

        if (DEBUG_PRINT_NATIVE_MEMORY || DEBUG_PRINT_IMAGE_COUNT) {
            debugPrint()
        }

        initialized = true
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

    private fun update() {
        val version = Settings.getVersionCode()
        if (version < 52) {
            GuideSettings.putGuideGallery(true)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ServiceRegistry.clientModule.clearMemoryCache()
            ServiceRegistry.dataModule.clearGalleryDetailCache()
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
                SimpleHandler.getInstance().postDelayed(this, DEBUG_PRINT_INTERVAL)
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
        ServiceRegistry.dataModule.clearGalleryDetailCache()
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
