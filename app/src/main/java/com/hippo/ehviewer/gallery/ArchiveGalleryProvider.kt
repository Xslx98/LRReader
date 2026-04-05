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

package com.hippo.ehviewer.gallery

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Process
import com.hippo.a7zip.ArchiveException
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.lib.glgallery.GalleryPageView
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import com.hippo.lib.yorozuya.thread.PriorityThread
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Stack
import java.util.concurrent.atomic.AtomicInteger

class ArchiveGalleryProvider(context: Context, uri: Uri) : GalleryProvider2() {

    private val file: UniFile? = UniFile.fromUri(context, uri)

    private var archiveThread: Thread? = null
    private var decodeThread: Thread? = null

    @Volatile
    private var archiveSize: Int = STATE_WAIT
    private var error: String? = null

    private val requests = Stack<Int>()
    private val extractingIndex = AtomicInteger(GalleryPageView.INVALID_INDEX)
    private val streams = LinkedHashMap<Int, InputStream>()
    private val decodingIndex = AtomicInteger(GalleryPageView.INVALID_INDEX)

    override fun start() {
        super.start()

        val id = sIdGenerator.incrementAndGet()

        archiveThread = PriorityThread(
            ArchiveTask(), "ArchiveTask-$id", Process.THREAD_PRIORITY_BACKGROUND
        ).also { it.start() }

        decodeThread = PriorityThread(
            DecodeTask(), "DecodeTask-$id", Process.THREAD_PRIORITY_BACKGROUND
        ).also { it.start() }
    }

    override fun stop() {
        super.stop()

        archiveThread?.interrupt()
        archiveThread = null
        decodeThread?.interrupt()
        decodeThread = null
    }

    override fun size(): Int = archiveSize

    override fun onRequest(index: Int) {
        val inDecodeTask: Boolean
        synchronized(streams) {
            inDecodeTask = streams.keys.contains(index) || index == decodingIndex.get()
        }

        synchronized(requests) {
            val inArchiveTask = requests.contains(index) || index == extractingIndex.get()
            if (!inArchiveTask && !inDecodeTask) {
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

    override fun getError(): String? = error

    override fun getImageFilename(index: Int): String {
        // LEGACY: archive entries lack original filenames; return index as fallback
        return index.toString()
    }

    override fun save(index: Int, file: UniFile): Boolean {
        // LEGACY: save not implemented for archive stream-based entries
        return false
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        // LEGACY: save-to-dir not implemented for archive stream-based entries
        return null
    }

    private inner class ArchiveTask : Runnable {
        override fun run() {
            var uraf: com.hippo.unifile.UniRandomAccessFile? = null
            if (file != null) {
                try {
                    uraf = file.createRandomAccessFile("r")
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            if (uraf == null) {
                archiveSize = STATE_ERROR
                error = GetText.getString(R.string.error_reading_failed)
                notifyDataChanged()
                return
            }

            var archive: A7ZipArchive? = null
            try {
                archive = A7ZipArchive.create(uraf)
            } catch (e: ArchiveException) {
                e.printStackTrace()
            }
            if (archive == null) {
                archiveSize = STATE_ERROR
                error = GetText.getString(R.string.error_invalid_archive)
                notifyDataChanged()
                return
            }

            val entries = archive.archiveEntries
            Collections.sort(entries, naturalComparator)

            // Update size and notify changed
            archiveSize = entries.size
            notifyDataChanged()

            loop@ while (!Thread.currentThread().isInterrupted) {
                var index = GalleryPageView.INVALID_INDEX
                var interrupted = false
                synchronized(requests) {
                    if (requests.isEmpty()) {
                        try {
                            (requests as Object).wait()
                        } catch (e: InterruptedException) {
                            interrupted = true
                        }
                    } else {
                        index = requests.pop()
                        extractingIndex.lazySet(index)
                    }
                }
                if (interrupted) break
                if (index == GalleryPageView.INVALID_INDEX) continue

                // Check index valid
                if (index < 0 || index >= entries.size) {
                    extractingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                    notifyPageFailed(index, GetText.getString(R.string.error_out_of_range))
                    continue
                }

                val pipe = Pipe(4 * 1024)

                var alreadyExists = false
                synchronized(streams) {
                    if (streams[index] != null) {
                        alreadyExists = true
                    } else {
                        streams[index] = pipe.inputStream
                        (streams as Object).notify()
                    }
                }
                if (alreadyExists) continue

                try {
                    entries[index].extract(pipe.outputStream)
                } catch (e: ArchiveException) {
                    e.printStackTrace()
                } finally {
                    extractingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                }
            }
        }
    }

    private inner class DecodeTask : Runnable {
        override fun run() {
            while (!Thread.currentThread().isInterrupted) {
                var index = GalleryPageView.INVALID_INDEX
                var stream: InputStream? = null
                var interrupted = false
                var castFailed = false
                synchronized(streams) {
                    if (streams.isEmpty()) {
                        try {
                            (streams as Object).wait()
                        } catch (e: InterruptedException) {
                            interrupted = true
                        }
                    } else {
                        val iterator = streams.entries.iterator()
                        val entry = iterator.next()
                        iterator.remove()
                        index = entry.key
                        try {
                            stream = entry.value
                        } catch (e: ClassCastException) {
                            notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
                            decodingIndex.lazySet(index)
                            castFailed = true
                        }
                        if (!castFailed) {
                            decodingIndex.lazySet(index)
                        }
                    }
                }
                if (interrupted) break
                if (castFailed) return
                if (stream == null) continue

                try {
                    val image = Image.decode(BitmapDrawable.createFromStream(stream, null), false)
                    if (image != null) {
                        notifyPageSucceed(index, image)
                    } else {
                        notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
                    }
                } finally {
                    decodingIndex.lazySet(GalleryPageView.INVALID_INDEX)
                }
            }
        }
    }

    companion object {
        private val sIdGenerator = AtomicInteger()

        private val naturalComparator =
            Comparator<A7ZipArchive.A7ZipArchiveEntry> { o1, o2 ->
                NaturalComparator().compare(o1.path, o2.path)
            }
    }
}
