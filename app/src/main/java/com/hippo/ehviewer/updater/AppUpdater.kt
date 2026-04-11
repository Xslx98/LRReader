package com.hippo.ehviewer.updater

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.ui.dialog.UpdateDialog
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.util.AppHelper.Companion.compareVersion
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

@Serializable
data class UpdateContent(
    @SerialName("title") val title: String = "",
    @SerialName("content") val content: List<String> = emptyList(),
    @SerialName("fileDownloadUrl") val fileDownloadUrl: String = ""
)

@Serializable
data class UpdateInfo(
    @SerialName("version") val version: String = "0.0.0",
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("mustUpdate") val mustUpdate: Boolean = false,
    @SerialName("updateContent") val updateContent: UpdateContent = UpdateContent()
)

class AppUpdater(private val name: String, source: okio.BufferedSource) {
    internal val updateData: UpdateInfo

    init {
        updateData = try {
            updateJson.decodeFromString<UpdateInfo>(source.readUtf8())
        } catch (_: Exception) {
            UpdateInfo()
        }
    }

    companion object {
        private const val TAG = "AppUpdater"

        private val updateJson = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: AppUpdater? = null

        // EH-LEGACY: multi-language lock not implemented, Chinese-only is sufficient
        private val lock: Lock = ReentrantLock()

        fun getInstance(context: Context): AppUpdater? {
            if (isPossible(context)) {
                return instance
            } else {
                instance = null
                return null
            }
        }

        fun isPossible(context: Context): Boolean {
            return getMetadata(context) != null
        }

        private fun getMetadata(context: Context): Array<String>? {
            val metadata = context.resources.getStringArray(R.array.update_metadata)
            return if (metadata.size == 2) {
                metadata
            } else {
                null
            }
        }

        @JvmStatic
        fun update(activity: Activity, manualChecking: Boolean) {
            val urls = getMetadata(activity)
            if (urls == null || urls.size != 2) {
                // Clear tags if it's not possible
                instance = null
                return
            }


            if (!UpdateSettings.getIsUpdateTime() && !manualChecking) {
                return
            }
            val dataName = urls[0]
            val dataUrl = urls[1]

            // Reject non-HTTPS update URLs to prevent MITM attacks
            if (!dataUrl.startsWith("https://")) {
                Log.w(TAG, "Refusing to fetch update data over non-HTTPS URL: $dataUrl")
                return
            }

            // Clear tags if name if different
            val tmp = instance
            if (tmp != null && tmp.name != dataName) {
                instance = null
            }

            (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                if (!lock.tryLock()) {
                    return@launch
                }
                try {
                    val dir = AppConfig.getFilesDir("update-json") ?: return@launch

                    val dataFile = File(dir, dataName)
                    // Read current AppUpdater
                    if (instance == null && dataFile.exists()) {
                        try {
                            dataFile.source().buffer().use { source ->
                                instance = AppUpdater(dataName, source)
                            }
                        } catch (e: IOException) {
                            FileUtils.delete(dataFile)
                        }
                    }

                    val client =
                        ServiceRegistry.networkModule.okHttpClient

                    // Save new json data
                    val tempDataFile = File(dir, "$dataName.tmp")
                    if (!save(client, dataUrl, tempDataFile)) {
                        if (manualChecking) {
                            UpdateDialog(activity).showCheckFailDialog()
                        }
                        FileUtils.delete(tempDataFile)
                        return@launch
                    }

                    val needUpdate: Boolean
                    val tempUpdateData = try {
                        val jsonText = FileUtils.read(tempDataFile)
                            ?: throw IOException("Failed to read update file")
                        updateJson.decodeFromString<UpdateInfo>(jsonText)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse update JSON", e)
                        FileUtils.delete(tempDataFile)
                        return@launch
                    }

                    // Check new data
                    needUpdate = if (instance != null) {
                        checkData(
                            instance!!.updateData,
                            tempUpdateData,
                            manualChecking
                        )
                    } else {
                        checkData(null, tempUpdateData, manualChecking)
                    }

                    if (!needUpdate) {
                        FileUtils.delete(tempDataFile)
                        if (manualChecking) {
                            ContextCompat.getMainExecutor(activity).execute {
                                Toast.makeText(activity, R.string.update_to_date, Toast.LENGTH_LONG)
                                    .show()
                            }
                        }
                        return@launch
                    }

                    // Replace current data with new data
                    FileUtils.delete(dataFile)
                    tempDataFile.renameTo(dataFile)

                    // Read new AppUpdater
                    try {
                        dataFile.source().buffer().use { source ->
                            instance = AppUpdater(dataName, source)
                        }
                    } catch (e: IOException) {
                        // Ignore
                    }
                    UpdateDialog(activity).showUpdateDialog(tempUpdateData)
                    UpdateSettings.putUpdateTime(Date().time)
                } finally {
                    lock.unlock()
                }
            }
        }

        private fun checkData(
            updateData: UpdateInfo?,
            tempUpdateData: UpdateInfo,
            manualChecking: Boolean
        ): Boolean {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val currentVersionCode = BuildConfig.VERSION_CODE
                var updateResult: Int
                if (updateData != null) {
                    val version1 = updateData.version
                    val version2 = tempUpdateData.version
                    updateResult = compareVersion(version1, version2)
                    if (updateResult < 0) {
                        return true
                    } else if (updateResult == 0) {
                        if (updateData.versionCode < tempUpdateData.versionCode) {
                            return true
                        }
                    }
                    if (!manualChecking) {
                        return false
                    }
                }
                updateResult = compareVersion(currentVersion, tempUpdateData.version)
                return if (updateResult < 0) {
                    true
                } else currentVersionCode < tempUpdateData.versionCode
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
                Analytics.recordException(e)
                return false
            }
        }

        private fun save(client: OkHttpClient, url: String, file: File): Boolean {
            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return false
                    }
                    val body = response.body ?: return false

                    body.byteStream().use { `is` ->
                        FileOutputStream(file).use { os ->
                            IOUtils.copy(`is`, os)
                        }
                    }
                    return true
                }
            } catch (t: Throwable) {
                ExceptionUtils.throwIfFatal(t)
                Analytics.recordException(t)
                return false
            }
        }
    }
}
