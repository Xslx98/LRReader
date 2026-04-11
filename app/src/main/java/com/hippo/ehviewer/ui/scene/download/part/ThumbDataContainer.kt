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
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.streampipe.InputStreamPipe
import com.hippo.unifile.UniFile
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture

/**
 * 缩略图数据容器
 *
 * Implements the Java [DataContainer] interface whose methods cannot be
 * made `suspend`. The download directory resolution (which requires a
 * suspend call to [SpiderDen.getGalleryDownloadDir]) is started eagerly
 * in a coroutine at construction time and the result is delivered via
 * [CompletableFuture]. The DataContainer callbacks are always invoked
 * from Conaco's background I/O threads, so blocking on the future is
 * safe and does not use `runBlocking`.
 */
class ThumbDataContainer(private val mInfo: DownloadInfo) : DataContainer {
    private var mFile: UniFile? = null

    /** Eagerly resolve download dir in background coroutine. */
    private val downloadDirFuture: CompletableFuture<UniFile?> = CompletableFuture<UniFile?>().also { future ->
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                future.complete(SpiderDen.getGalleryDownloadDir(mInfo))
            } catch (e: Exception) {
                future.complete(null)
            }
        }
    }

    private fun ensureFile() {
        if (mFile == null) {
            // Called from Conaco's background I/O threads, blocking is safe
            val dir = downloadDirFuture.get()
            if (dir != null && dir.isDirectory()) {
                mFile = dir.createFile(".thumb")
            }
        }
    }

    override fun isEnabled(): Boolean {
        ensureFile()
        return mFile != null
    }

    override fun onUrlMoved(requestUrl: String?, responseUrl: String?) {
    }

    override fun save(
        `is`: InputStream,
        length: Long,
        mediaType: String?,
        notify: ProgressNotifier?
    ): Boolean {
        ensureFile()
        if (mFile == null) {
            return false
        }

        var os: OutputStream? = null
        try {
            os = mFile!!.openOutputStream()
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
        ensureFile()
        if (mFile != null) {
            return UniFileInputStreamPipe(mFile)
        } else {
            return null
        }
    }

    override fun remove() {
        if (mFile != null) {
            mFile!!.delete()
        }
    }
}
