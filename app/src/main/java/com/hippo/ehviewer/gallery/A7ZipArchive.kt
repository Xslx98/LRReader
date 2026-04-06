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

import com.hippo.a7zip.ArchiveException
import com.hippo.a7zip.InArchive
import com.hippo.a7zip.PropID
import com.hippo.a7zip.PropType
import com.hippo.a7zip.SeekableInputStream
import com.hippo.unifile.UniRandomAccessFile
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * 2022/4/17
 * 今天心情莫名其妙
 * 晚上六点边吃晚饭边看了个电影《SuperFast！》无厘头恶搞电影
 * 看完困得要死，睡到九点半起床去健个身
 * 大晚上的摩托车风吹着好冷。。。
 *
 * 此类在64位应用中会抛出异常
 * 正在寻求替代方案
 */
@Deprecated("This class throws exceptions in 64-bit apps. Seeking alternative.")
class A7ZipArchive private constructor(private val archive: InArchive) : Closeable {

    override fun close() {
        archive.close()
    }

    val archiveEntries: MutableList<A7ZipArchiveEntry>
        get() {
            val entries = mutableListOf<A7ZipArchiveEntry>()

            for (i in 0 until archive.numberOfEntries) {
                if (!archive.getEntryBooleanProperty(i, PropID.ENCRYPTED)
                    && !archive.getEntryBooleanProperty(i, PropID.IS_DIR)
                    && !archive.getEntryBooleanProperty(i, PropID.IS_VOLUME)
                    && !archive.getEntryBooleanProperty(i, PropID.SOLID)
                ) {
                    val path = archive.getEntryPath(i)
                    if (isSupportedFilename(path.lowercase())) {
                        entries.add(A7ZipArchiveEntry(archive, i, path))
                    }
                }
            }

            return entries
        }

    class A7ZipArchiveEntry internal constructor(
        private val archive: InArchive,
        private val index: Int,
        val path: String
    ) {
        @Throws(ArchiveException::class)
        fun extract(os: OutputStream) {
            archive.extractEntry(index, OutputStreamSequentialOutStream(os))
        }
    }

    private class UniRandomAccessFileInStream(
        private val file: UniRandomAccessFile
    ) : SeekableInputStream() {

        private val base = ByteArray(8)

        @Throws(IOException::class)
        override fun seek(pos: Long) {
            file.seek(pos)
        }

        @Throws(IOException::class)
        override fun tell(): Long = file.filePointer

        @Throws(IOException::class)
        override fun size(): Long = file.length()

        @Throws(IOException::class)
        override fun read(): Int {
            return if (file.read(base, 0, 1) != -1) base[0].toInt() and 0xff else -1
        }

        @Throws(IOException::class)
        override fun read(bytes: ByteArray): Int = file.read(bytes, 0, bytes.size)

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)

        @Throws(IOException::class)
        override fun close() {
            file.close()
        }
    }

    private class OutputStreamSequentialOutStream(
        private val stream: OutputStream
    ) : OutputStream() {

        @Throws(IOException::class)
        override fun write(b: Int) {
            stream.write(b)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            stream.write(b, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            stream.close()
        }
    }

    companion object {
        private fun isSupportedFilename(name: String): Boolean {
            return GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.any { name.endsWith(it) }
        }

        @JvmStatic
        @Throws(ArchiveException::class)
        fun create(file: UniRandomAccessFile): A7ZipArchive {
            val store: SeekableInputStream = UniRandomAccessFileInStream(file)
            val archive = InArchive.open(store)
            if ((archive.getArchivePropertyType(PropID.ENCRYPTED) == PropType.BOOL && archive.getArchiveBooleanProperty(PropID.ENCRYPTED))
                || (archive.getArchivePropertyType(PropID.SOLID) == PropType.BOOL && archive.getArchiveBooleanProperty(PropID.SOLID))
                || (archive.getArchivePropertyType(PropID.IS_VOLUME) == PropType.BOOL && archive.getArchiveBooleanProperty(PropID.IS_VOLUME))
            ) {
                throw ArchiveException("Unsupported archive")
            }
            return A7ZipArchive(archive)
        }
    }
}
