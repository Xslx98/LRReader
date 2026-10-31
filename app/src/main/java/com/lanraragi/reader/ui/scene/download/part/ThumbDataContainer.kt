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
package com.lanraragi.reader.ui.scene.download.part

import android.util.Log
import com.lanraragi.reader.storage.StorageFile
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.cover.CoverStore
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 缩略图数据容器
 *
 * Implements [CoverStore], whose methods are blocking (they run on a cover
 * loader I/O thread). The download directory resolution (which requires a
 * suspend call to
 * [com.lanraragi.reader.spider.SpiderDen.findGalleryDownloadDir]) is shared
 * per archive through
 * [com.lanraragi.reader.ui.scene.download.DownloadDirCache], so rebinding
 * the same row never restarts it; the result is delivered via
 * [downloadDirFuture].
 *
 * The callbacks run on the cover loader's I/O threads, so this class must
 * never stall them for long:
 * - the future is awaited **once** per container instance with a short
 *   bound; an unresolved/failed/hung resolution just disables the
 *   container, and the load degrades gracefully to the shared disk cache +
 *   network. The shared future keeps resolving in the background,
 *   so the next bind (a fresh container over the same future) retries.
 * - pure read callbacks ([isEnabled]/[get]) never create files; only
 *   [save] creates `.thumb`. The old create-on-read behavior littered
 *   zero-byte `.thumb` files into archive directories.
 */
class ThumbDataContainer(
    private val downloadDirFuture: CompletableFuture<StorageFile?>,
    private val dirWaitMillis: Long = DIR_WAIT_MILLIS,
) : CoverStore {

    private var dirResolved = false
    private var dir: StorageFile? = null

    /** Bounded, once-per-instance resolution of the download directory. */
    private fun resolveDir(): StorageFile? {
        if (!dirResolved) {
            dirResolved = true
            dir = try {
                downloadDirFuture.get(dirWaitMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                null
            } catch (e: ExecutionException) {
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }?.takeIf { it.isDirectory }
        }
        return dir
    }

    /** Existing `.thumb` file or null — read paths must not create files. */
    private fun existingFile(): StorageFile? =
        resolveDir()?.subFile(THUMB_FILE_NAME)?.takeIf { it.isFile }

    override fun isEnabled(): Boolean {
        return resolveDir() != null
    }

    override fun save(input: InputStream): Boolean {
        val file = resolveDir()?.createFile(THUMB_FILE_NAME) ?: return false

        return try {
            file.openOutputStream().use { input.copyTo(it) }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    override fun open(): InputStream? {
        return try {
            existingFile()?.openInputStream()
        } catch (e: IOException) {
            // Unreadable .thumb: the load falls back to cache and network.
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot read $THUMB_FILE_NAME", e)
            null
        }
    }

    override fun remove() {
        existingFile()?.delete()
    }

    companion object {
        private const val TAG = "ThumbDataContainer"
        private const val THUMB_FILE_NAME = ".thumb"

        /**
         * Typical resolution is a Room lookup plus a directory probe
         * (tens of ms). 250 ms covers the slow tail while capping the
         * worst-case stall a single bind can inflict on a loader I/O
         * thread; past the bound the container silently degrades.
         */
        private const val DIR_WAIT_MILLIS = 250L
    }
}
