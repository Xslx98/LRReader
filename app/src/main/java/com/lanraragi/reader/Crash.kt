/*
 * Copyright 2019 Hippo Seven
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

package com.lanraragi.reader

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.lanraragi.framework.lib.yorozuya.FileUtils
import com.lanraragi.framework.lib.yorozuya.OSUtils
import com.lanraragi.framework.scene.StageActivity
import com.lanraragi.framework.util.PackageUtils
import com.lanraragi.reader.diagnostics.CrashLogStore
import com.lanraragi.reader.diagnostics.DiagLog
import com.lanraragi.reader.settings.PrivacySettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Writes crash and non-fatal reports into the app-private [CrashLogStore]
 * (audit 2026-10-04 C06). Reports carry no device serial, build host or build
 * user (STAB-23) and end with the recent [DiagLog] lines as breadcrumbs.
 */
object Crash {

    private const val TAG = "Crash"
    private const val BREADCRUMB_LINES = 60
    private const val SIGNATURE_FRAMES = 4
    private const val MAX_NON_FATAL_PER_PROCESS = 20

    /** Non-fatal signatures already written by this process (one file per distinct bug). */
    private val nonFatalSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    fun store(): CrashLogStore? = AppConfig.getCrashDir()?.let { CrashLogStore(it) }

    /** The process is about to die on [t]. */
    @JvmStatic
    fun saveCrashLog(context: Context, t: Throwable) {
        save(context, CrashLogStore.Kind.CRASH, t)
    }

    /**
     * [t] reached a coroutine exception handler: the app survives, but the
     * failed job silently did nothing (STAB-02). Each distinct stack is
     * written once per process; honours the "save crash log" switch.
     */
    @JvmStatic
    fun saveNonFatal(context: Context, t: Throwable) {
        if (!saveEnabled()) return
        if (nonFatalSeen.size >= MAX_NON_FATAL_PER_PROCESS) return
        if (!nonFatalSeen.add(signature(t))) return
        save(context, CrashLogStore.Kind.NON_FATAL, t)
    }

    private val nonFatalWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "crash-nonfatal").apply { isDaemon = true }
    }

    /**
     * Context-free entry for [com.lanraragi.reader.module.CoroutineModule]'s
     * handler, which may run on Main: the report is written on a background
     * thread, and any failure while writing it is logged, never rethrown.
     */
    @JvmStatic
    fun saveNonFatal(t: Throwable) {
        val app = appOrNull()
        if (app == null) return
        nonFatalWriter.execute {
            try {
                saveNonFatal(app, t)
            } catch (e: Throwable) {
                Log.e(TAG, "Write non-fatal report", e)
            }
        }
    }

    // Boot-scope failures can arrive before Settings is initialised: record them.
    private fun saveEnabled(): Boolean = try {
        PrivacySettings.getSaveCrashLog()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Crash-log switch unreadable; saving anyway", e)
        true
    }

    private fun appOrNull(): Context? = try {
        LRReaderApplication.instance
    } catch (e: UninitializedPropertyAccessException) {
        Log.w(TAG, "No application instance for a non-fatal report", e)
        null
    }

    private fun save(context: Context, kind: CrashLogStore.Kind, t: Throwable) {
        val store = store()
        if (store == null) {
            Log.e(TAG, "No crash directory; dropping ${kind.name} report")
            return
        }
        try {
            val report = buildReport(
                header = collectInfo(context),
                threadName = Thread.currentThread().name,
                t = t,
                breadcrumbs = DiagLog.ring.tail(BREADCRUMB_LINES),
            )
            store.write(kind, report)
        } catch (e: Exception) {
            Log.e(TAG, "Write ${kind.name} report", e)
        }
    }

    /** Groups repeats of one bug: exception class plus the top frames. */
    internal fun signature(t: Throwable): String =
        t.javaClass.name + t.stackTrace.take(SIGNATURE_FRAMES).joinToString(prefix = "@") { it.toString() }

    internal fun buildReport(
        header: String,
        threadName: String,
        t: Throwable,
        breadcrumbs: List<String>,
    ): String = buildString {
        append(header)
        append("======== CrashInfo ========\n")
        append("Thread=").append(threadName).append('\n')
        // stackTraceToString already walks the cause chain; never loop causes again.
        append(t.stackTraceToString())
        append('\n')
        append("======== Recent events ========\n")
        if (breadcrumbs.isEmpty()) append("(none)\n")
        breadcrumbs.forEach { append(it).append('\n') }
    }

    private fun collectInfo(context: Context): String = buildString {
        append("TIME=").append(System.currentTimeMillis()).append('\n').append('\n')
        append("======== PackageInfo ========\n")
        append("PackageName=").append(context.packageName).append('\n')
        append("VersionName=").append(BuildConfig.VERSION_NAME).append('\n')
        append("VersionCode=").append(BuildConfig.VERSION_CODE).append('\n')
        append("BuildType=").append(BuildConfig.BUILD_TYPE).append('\n')
        append("Signature=").append(PackageUtils.getSignature(context, context.packageName)).append('\n')
        append('\n')

        var topActivity = "null"
        var topScene = "null"
        try {
            val activity = (context.applicationContext as LRReaderApplication).topActivity
            if (activity != null) {
                topActivity = activity.javaClass.name
                if (activity is StageActivity) topScene = activity.topSceneClass?.name ?: "null"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Retrieve top activity", e)
        }
        append("======== Runtime ========\n")
        append("TopActivity=").append(topActivity).append('\n')
        append("TopScene=").append(topScene).append('\n')
        append('\n')

        append("======== DeviceInfo ========\n")
        append("MANUFACTURER=").append(Build.MANUFACTURER).append('\n')
        append("MODEL=").append(Build.MODEL).append('\n')
        append("DEVICE=").append(Build.DEVICE).append('\n')
        append("PRODUCT=").append(Build.PRODUCT).append('\n')
        append("BOARD=").append(Build.BOARD).append('\n')
        append("HARDWARE=").append(Build.HARDWARE).append('\n')
        append("ABIS=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        append("FINGERPRINT=").append(Build.FINGERPRINT).append('\n')
        append("RELEASE=").append(Build.VERSION.RELEASE).append('\n')
        append("SDK=").append(Build.VERSION.SDK_INT).append('\n')
        append("MEMORY=").append(FileUtils.humanReadableByteCount(OSUtils.getAppAllocatedMemory(), false))
            .append('\n')
        append("MEMORY_NATIVE=")
            .append(FileUtils.humanReadableByteCount(Debug.getNativeHeapAllocatedSize(), false)).append('\n')
        append("MEMORY_MAX=").append(FileUtils.humanReadableByteCount(OSUtils.getAppMaxMemory(), false))
            .append('\n')
        append("MEMORY_TOTAL=").append(FileUtils.humanReadableByteCount(OSUtils.getTotalMemory(), false))
            .append('\n')
        append('\n')
    }
}
