package com.lanraragi.reader.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.lanraragi.framework.content.FileProvider
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.Crash
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.ServerCapabilityCache
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.settings.PrivacySettings
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android glue for the "Share diagnostics" bundle (audit 2026-10-04 C06, R2):
 * gathers the facts, writes the zip into `cacheDir/diagnostics` (the only
 * copy, replaced on every share) and builds the `ACTION_SEND` intent.
 */
object DiagnosticsCollector {

    private const val TAG = "DiagnosticsCollector"
    private const val DIR = "diagnostics"
    private const val MIME = "application/zip"

    /** Download queue counts; must run on the main thread (DownloadManager's contract). */
    @MainThread
    fun downloadSummary(): DiagnosticsInfo.DownloadSummary? = try {
        val dm = ServiceRegistry.dataModule.downloadManager
        val infos = dm.allDownloadInfoList
        DiagnosticsInfo.DownloadSummary(
            byState = infos.groupingBy { it.state }.eachCount(),
            failures = infos.filter { it.state == com.lanraragi.reader.download.DownloadState.FAILED }
                .groupingBy { it.failureReason }.eachCount(),
            labels = dm.labelList.size,
        )
    } catch (e: RuntimeException) {
        Log.e(TAG, "Download summary", e)
        null
    }

    /** Builds the zip. Blocking: disk, database and logcat. */
    @WorkerThread
    fun build(context: Context, downloads: DiagnosticsInfo.DownloadSummary?): File {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        val reports = Crash.store()?.list().orEmpty()
        val serverUrl = guard("server url") { LRRAuthManager.getServerUrl() }
        val info = DiagnosticsInfo(
            app = listOf(
                "Package" to context.packageName,
                "VersionName" to BuildConfig.VERSION_NAME,
                "VersionCode" to BuildConfig.VERSION_CODE.toString(),
                "BuildType" to BuildConfig.BUILD_TYPE,
                "Flavor" to BuildConfig.FLAVOR,
            ),
            device = listOf(
                "Manufacturer" to Build.MANUFACTURER,
                "Model" to Build.MODEL,
                "Release" to Build.VERSION.RELEASE,
                "SDK" to Build.VERSION.SDK_INT.toString(),
                "ABIs" to Build.SUPPORTED_ABIS.joinToString(","),
                "Locale" to Locale.getDefault().toLanguageTag(),
            ),
            databaseVersion = guard("database version") {
                AppDatabase.getInstance(context).openHelper.readableDatabase.version
            },
            crashLogEnabled = PrivacySettings.getSaveCrashLog(),
            reportCount = reports.size,
            serverUrl = serverUrl,
            serverInfo = serverUrl?.let { ServerCapabilityCache.describeServerInfo(it) },
            downloads = downloads,
        )
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "lrreader-diagnostics-$stamp.zip")
        DiagnosticsBundle(object : DiagnosticsBundle.Sources {
            override fun info() = info.render()
            override fun settings(): Map<String, *> = Settings.getPreferences().all
            override fun events() = DiagLog.ring.snapshot()
            override fun reports() = reports
            override fun writeLogcat(out: Writer) = LogcatDump.writeTo(out)
        }).writeTo(file)
        return file
    }

    fun shareIntent(context: Context, file: File, chooserTitle: CharSequence): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LR Reader ${BuildConfig.VERSION_NAME} diagnostics")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "Collect $what", e)
        null
    }
}
