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
import android.content.Context
import android.util.Log
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.util.ReadableTime
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object AppConfig {

    private const val TAG = "AppConfig"
    private const val APP_DIRNAME = "LRReader"

    private const val DOWNLOAD = "download"
    private const val TEMP = "temp"
    private const val ARCHIVER = "archiver"
    private const val IMAGE = "image"
    private const val PARSE_ERROR = "parse_error"
    private const val LOGCAT = "logcat"
    private const val DATA = "data"
    private const val CRASH = "crash"

    @Volatile
    private var sDeletingOldParseErrorFiles = false
    private val sDeleteOldParseErrorFilesLock = Any()

    @SuppressLint("StaticFieldLeak") // Safe: holds Application Context, not Activity
    private lateinit var sContext: Context

    @JvmStatic
    fun initialize(context: Context) {
        sContext = context.applicationContext
    }

    @JvmStatic
    fun getExternalAppDir(): File? {
        if (!::sContext.isInitialized) return null
        val dir = sContext.getExternalFilesDir(null)
        return if (dir != null && FileUtils.ensureDirectory(dir)) dir else null
    }

    /**
     * mkdirs and get
     */
    @JvmStatic
    fun getDirInExternalAppDir(filename: String): File? {
        val appFolder = getExternalAppDir() ?: return null
        val dir = File(appFolder, filename)
        return if (FileUtils.ensureDirectory(dir)) dir else null
    }

    @JvmStatic
    fun getFileInExternalAppDir(filename: String): File? {
        val appFolder = getExternalAppDir() ?: return null
        val file = File(appFolder, filename)
        return if (FileUtils.ensureFile(file)) file else null
    }

    @JvmStatic
    fun getDefaultDownloadDir(): File? = getDirInExternalAppDir(DOWNLOAD)

    @JvmStatic
    fun getExternalTempDir(): File? = getDirInExternalAppDir(TEMP)

    @JvmStatic
    fun getExternalArchiverDir(): File? = getDirInExternalAppDir(ARCHIVER)

    @JvmStatic
    fun getExternalImageDir(): File? = getDirInExternalAppDir(IMAGE)

    @JvmStatic
    fun getExternalParseErrorDir(): File? = getDirInExternalAppDir(PARSE_ERROR)

    @JvmStatic
    fun getExternalLogcatDir(): File? = getDirInExternalAppDir(LOGCAT)

    @JvmStatic
    fun getExternalDataDir(): File? = getDirInExternalAppDir(DATA)

    @JvmStatic
    fun getExternalCrashDir(): File? = getDirInExternalAppDir(CRASH)

    @JvmStatic
    fun getTempDir(): File? {
        val dir = sContext.cacheDir ?: return null
        val file = File(dir, TEMP)
        return if (FileUtils.ensureDirectory(file)) file else null
    }

    @JvmStatic
    fun getArchiverDir(): File? {
        val dir = sContext.cacheDir ?: return null
        val file = File(dir, ARCHIVER)
        return if (FileUtils.ensureDirectory(file)) file else null
    }

    @JvmStatic
    fun createTempFile(): File? = FileUtils.createTempFile(getTempDir(), null)

    @JvmStatic
    fun saveParseErrorBody(e: ParseException) {
        val dir = getExternalParseErrorDir() ?: return

        val file = File(dir, ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".txt")
        var os: FileOutputStream? = null
        try {
            os = FileOutputStream(file)
            val message = e.message
            val body = e.body
            if (message != null) {
                os.write(message.toByteArray(StandardCharsets.UTF_8))
                os.write('\n'.code)
            }
            if (body != null) {
                os.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            os.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Save parse error body", e)
        } finally {
            IOUtils.closeQuietly(os)
        }
    }

    @JvmStatic
    fun deleteOldParseErrorFiles() {
        val dir = getExternalParseErrorDir()
        if (dir == null || !dir.isDirectory) {
            return
        }

        synchronized(sDeleteOldParseErrorFilesLock) {
            if (sDeletingOldParseErrorFiles) {
                return
            }
            sDeletingOldParseErrorFiles = true
        }

        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val names = dir.list() ?: return@launch
                val threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
                for (name in names) {
                    val file = File(dir, name)
                    if (file.isFile && file.lastModified() < threeDaysAgo) {
                        file.delete()
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Delete old parse error files ran out of memory", e)
                // 内存不足时静默放弃，并尝试删除整个目录，避免崩溃
                dir.delete()
            } finally {
                synchronized(sDeleteOldParseErrorFilesLock) {
                    sDeletingOldParseErrorFiles = false
                }
            }
        }
    }

    @JvmStatic
    fun getFilesDir(name: String): File? {
        var dir: File = sContext.filesDir ?: return null
        dir = File(dir, name)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }
}
