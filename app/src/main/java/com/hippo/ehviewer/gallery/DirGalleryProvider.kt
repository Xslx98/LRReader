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
package com.hippo.ehviewer.gallery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.runSuspend
import com.hippo.lib.glgallery.GalleryPageView
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.lib.yorozuya.thread.PriorityThread
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import java.util.Stack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class DirGalleryProvider : GalleryProvider2, Runnable {

    private val dir: UniFile
    private val context: Context?
    private val gid: Long
    private val arcId: String?
    private val serverUrl: String?
    private var startPageValue: Int = 0

    private val requests = Stack<Int>()
    private val decodingIndex = AtomicInteger(GalleryPageView.INVALID_INDEX)
    private val fileList = AtomicReference<Array<UniFile>?>()
    private var bgThread: Thread? = null

    @Volatile
    private var sizeValue: Int = STATE_WAIT
    private var errorMessage: String? = null

    /** Legacy constructor (no progress tracking). */
    constructor(dir: UniFile) {
        this.dir = dir
        this.context = null
        this.gid = 0
        this.arcId = null
        this.serverUrl = null
    }

    /** Constructor with Context and GalleryInfo for reading progress persistence. */
    constructor(dir: UniFile, context: Context, galleryInfo: GalleryInfo) {
        this.dir = dir
        this.context = context.applicationContext
        this.gid = galleryInfo.gid
        this.arcId = galleryInfo.token // LANraragi arcid
        this.serverUrl = LRRAuthManager.getServerUrl()
        this.startPageValue = loadReadingProgress(this.context!!, gid)
    }

    override fun getStartPage(): Int = startPageValue

    override fun putStartPage(page: Int) {
        startPageValue = page
        if (context != null && gid != 0L) {
            saveReadingProgress(context, gid, page)
        }
        // Sync progress to LANraragi server (1-indexed)
        if (arcId != null && serverUrl != null) {
            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    val client = ServiceRegistry.networkModule.okHttpClient
                    runSuspend<Unit> {
                        LRRArchiveApi.updateProgress(client, serverUrl, arcId, page + 1)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync progress: ${e.message}")
                }
            }
        }
    }

    override fun start() {
        super.start()

        // Async: load server progress and jump if newer
        if (arcId != null && serverUrl != null && context != null) {
            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    val client = ServiceRegistry.networkModule.okHttpClient
                    val metadata = runSuspend {
                        LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcId)
                    }
                    Log.i(TAG, "[PROGRESS] Server metadata: progress=${metadata.progress}" +
                            " lastreadtime=${metadata.lastreadtime}")
                    if (metadata.progress > 0) {
                        val serverPage0 = metadata.progress - 1
                        val serverTs = metadata.lastreadtime
                        val localTs = loadReadingTimestamp(context, gid)
                        Log.i(TAG, "[PROGRESS] serverPage0=$serverPage0" +
                                " serverTs=$serverTs localTs=$localTs" +
                                " localPage=$startPageValue")
                        val resolvedPage: Int
                        if (serverTs > localTs) {
                            resolvedPage = serverPage0
                            startPageValue = serverPage0
                            saveReadingProgress(context, gid, serverPage0)
                            Log.i(TAG, "[PROGRESS] Using SERVER progress: page $serverPage0")
                        } else if (localTs > serverTs && startPageValue > 0) {
                            resolvedPage = startPageValue
                            Log.i(TAG, "[PROGRESS] Using LOCAL progress: page $startPageValue")
                        } else {
                            resolvedPage = max(serverPage0, startPageValue)
                            startPageValue = resolvedPage
                            Log.i(TAG, "[PROGRESS] Timestamps equal, using max: page $resolvedPage")
                        }
                        // Jump GalleryView if needed
                        if (resolvedPage > 0) {
                            val gv = galleryView
                            if (gv != null) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    gv.setCurrentPage(resolvedPage)
                                    Log.i(TAG, "[PROGRESS] setCurrentPage($resolvedPage) called")
                                }, 300)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PROGRESS] Failed to load server progress: ${e.message}")
                }
            }
        }

        bgThread = PriorityThread(this, "$TAG-${sIdGenerator.incrementAndGet()}",
                Process.THREAD_PRIORITY_BACKGROUND)
        bgThread!!.start()
    }

    override fun stop() {
        super.stop()
        bgThread?.interrupt()
        bgThread = null
    }

    override fun size(): Int = sizeValue

    override fun onRequest(index: Int) {
        synchronized(requests) {
            if (!requests.contains(index) && index != decodingIndex.get()) {
                requests.add(index)
                (requests as Object).notify()
            }
        }
        notifyPageWait(index)
    }

    override fun onForceRequest(index: Int) {
        onRequest(index)
    }

    override fun onCancelRequest(index: Int) {
        synchronized(requests) {
            requests.remove(Integer.valueOf(index))
        }
    }

    override fun getError(): String? = errorMessage

    override fun getImageFilename(index: Int): String {
        // LEGACY: local files use index as filename fallback
        return index.toString()
    }

    override fun save(index: Int, file: UniFile): Boolean {
        val files = fileList.get() ?: return false
        if (index < 0 || index >= files.size) {
            return false
        }
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.OutputStream? = null
        return try {
            inputStream = files[index].openInputStream()
            outputStream = file.openOutputStream()
            IOUtils.copy(inputStream, outputStream)
            true
        } catch (e: IOException) {
            false
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val files = fileList.get() ?: return null
        if (index < 0 || index >= files.size) {
            return null
        }
        val src = files[index]
        val extension = FileUtils.getExtensionFromFilename(src.name)
        val dst = dir.subFile(if (extension != null) "$filename.$extension" else filename)
                ?: return null
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.OutputStream? = null
        return try {
            inputStream = src.openInputStream()
            outputStream = dst.openOutputStream()
            IOUtils.copy(inputStream, outputStream)
            dst
        } catch (e: IOException) {
            null
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(outputStream)
        }
    }

    override fun run() {
        // It may take a long time, so run it in new thread
        val files = dir.listFiles(imageFilter)

        if (files == null) {
            sizeValue = STATE_ERROR
            errorMessage = GetText.getString(R.string.error_not_folder_path)

            // Notify to show error
            notifyDataChanged()

            Log.i(TAG, "ImageDecoder end with error")
            return
        }

        // Sort it
        files.sortWith(naturalComparator)

        // Put file list
        fileList.lazySet(files)

        // Set state normal and notify
        sizeValue = files.size
        notifyDataChanged()

        var interrupted = false
        while (!interrupted && !Thread.currentThread().isInterrupted) {
            val index: Int = synchronized(requests) {
                if (requests.isEmpty()) {
                    try {
                        (requests as Object).wait()
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                    return@synchronized -1
                }
                val i = requests.pop()
                decodingIndex.lazySet(i)
                i
            }

            if (interrupted) break
            if (index == -1) continue

            // Check index valid
            if (index < 0 || index >= files.size) {
                decodingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                notifyPageFailed(index, GetText.getString(R.string.error_out_of_range))
                continue
            }

            var inputStream: java.io.InputStream? = null
            try {
                inputStream = files[index].openInputStream()
                val image = Image.decode(inputStream as FileInputStream, false)
                decodingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                if (image != null) {
                    notifyPageSucceed(index, image)
                } else {
                    notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
                }
            } catch (e: IOException) {
                decodingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                notifyPageFailed(index, GetText.getString(R.string.error_reading_failed))
            } finally {
                IOUtils.closeQuietly(inputStream)
            }
            decodingIndex.lazySet(GalleryPageView.INVALID_INDEX)
        }

        // Clear file list
        fileList.lazySet(null)

        Log.i(TAG, "ImageDecoder end")
    }

    companion object {
        private val TAG = DirGalleryProvider::class.java.simpleName
        private val sIdGenerator = AtomicInteger()

        private val imageFilter = com.hippo.unifile.FilenameFilter { _, name ->
            StringUtils.endsWith(name.lowercase(), GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
        }

        private val naturalComparator = Comparator<UniFile> { o1, o2 ->
            NaturalComparator().compare(o1.name, o2.name)
        }
    }
}
