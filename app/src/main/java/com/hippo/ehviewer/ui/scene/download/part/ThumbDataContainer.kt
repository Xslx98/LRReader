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
package com.hippo.ehviewer.ui.scene.download.part

import com.hippo.conaco.DataContainer
import com.hippo.conaco.ProgressNotifier
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.streampipe.InputStreamPipe
import com.hippo.unifile.UniFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 缩略图数据容器
 *
 * Implements the Java [DataContainer] interface whose methods cannot be
 * made `suspend`. The download directory resolution (which requires a
 * suspend call to
 * [com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir]) is shared
 * per archive through
 * [com.hippo.ehviewer.ui.scene.download.DownloadDirCache], so rebinding
 * the same row never restarts it; the result is delivered via
 * [downloadDirFuture].
 *
 * The callbacks run on Conaco's **single app-wide serial disk thread**
 * (audit FW-2), so this class must never stall it:
 * - the future is awaited **once** per container instance with a short
 *   bound; an unresolved/failed/hung resolution just disables the
 *   container, and Conaco degrades gracefully to its own URL-keyed disk
 *   cache + network. The shared future keeps resolving in the background,
 *   so the next bind (a fresh container over the same future) retries.
 * - pure read callbacks ([isEnabled]/[get]) never create files; only
 *   [save] creates `.thumb`. The old create-on-read behavior littered
 *   zero-byte `.thumb` files into archive directories.
 */
class ThumbDataContainer(
    private val downloadDirFuture: CompletableFuture<UniFile?>,
    private val dirWaitMillis: Long = DIR_WAIT_MILLIS,
) : DataContainer {

    private var dirResolved = false
    private var dir: UniFile? = null

    /** Bounded, once-per-instance resolution of the download directory. */
    private fun resolveDir(): UniFile? {
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
    private fun existingFile(): UniFile? =
        resolveDir()?.subFile(THUMB_FILE_NAME)?.takeIf { it.isFile }

    override fun isEnabled(): Boolean {
        return resolveDir() != null
    }

    override fun onUrlMoved(requestUrl: String?, responseUrl: String?) {
    }

    override fun save(
        `is`: InputStream,
        length: Long,
        mediaType: String?,
        notify: ProgressNotifier?
    ): Boolean {
        val file = resolveDir()?.createFile(THUMB_FILE_NAME) ?: return false

        var os: OutputStream? = null
        try {
            os = file.openOutputStream()
            IOUtils.copy(`is`, os)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            IOUtils.closeQuietly(os)
        }
    }

    override fun get(): InputStreamPipe? {
        return existingFile()?.let { UniFileInputStreamPipe(it) }
    }

    override fun remove() {
        existingFile()?.delete()
    }

    companion object {
        private const val THUMB_FILE_NAME = ".thumb"

        /**
         * Typical resolution is a Room lookup plus a SAF directory probe
         * (tens of ms). 250 ms covers the slow tail while capping the
         * worst-case stall a single bind can inflict on the shared serial
         * disk thread; past the bound the container silently degrades.
         */
        private const val DIR_WAIT_MILLIS = 250L
    }
}
