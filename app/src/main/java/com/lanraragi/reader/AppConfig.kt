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

package com.lanraragi.reader

import android.annotation.SuppressLint
import android.content.Context
import com.lanraragi.framework.lib.yorozuya.FileUtils
import java.io.File

object AppConfig {

    private const val APP_DIRNAME = "LRReader"

    private const val DOWNLOAD = "download"
    private const val TEMP = "temp"
    private const val ARCHIVER = "archiver"
    private const val IMAGE = "image"
    private const val PARSE_ERROR = "parse_error"
    private const val DATA = "data"
    private const val CRASH = "crash"

    @Volatile

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
    fun getExternalDataDir(): File? = getDirInExternalAppDir(DATA)

    /**
     * App-private crash, non-fatal and exit-reason reports (audit 2026-10-04 C06).
     * Was `Android/data/.../crash`, which users on API 30+ cannot open.
     */
    @JvmStatic
    fun getCrashDir(): File? = if (::sContext.isInitialized) getFilesDir(CRASH) else null

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

    /**
     * Deletes the external `parse_error` directory, where older builds could
     * dump raw server responses readable outside the app (audit C49 /
     * SEC-19). Nothing writes there any more. Call off the main thread.
     */
    @JvmStatic
    fun purgeLegacyParseErrorDir() {
        if (!::sContext.isInitialized) return
        val external = sContext.getExternalFilesDir(null) ?: return
        val dir = File(external, PARSE_ERROR)
        if (dir.exists()) dir.deleteRecursively()
    }

    @JvmStatic
    fun getFilesDir(name: String): File? {
        var dir: File = sContext.filesDir ?: return null
        dir = File(dir, name)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }
}
