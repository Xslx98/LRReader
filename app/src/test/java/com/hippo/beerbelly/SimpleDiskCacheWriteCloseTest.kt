package com.hippo.beerbelly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Regression tests for the disk-cache write path leaking the underlying
 * FileOutputStream: DiskLruCache.Editor.commit() renames dirty→clean but
 * never closes the stream, so every successful thumbnail write leaked one
 * fd until GC finalization. Both write entry points must close the stream
 * before committing.
 */
class SimpleDiskCacheWriteCloseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newCache() = SimpleDiskCache(tmp.newFolder(), 4 * 1024 * 1024)

    /** Unwraps FaultHidingOutputStream (a FilterOutputStream) to the FileOutputStream. */
    private fun underlyingFile(os: OutputStream): FileOutputStream {
        var cur: OutputStream = os
        while (cur is FilterOutputStream) {
            val field = FilterOutputStream::class.java.getDeclaredField("out")
            field.isAccessible = true
            cur = field.get(cur) as OutputStream
        }
        return cur as FileOutputStream
    }

    @Test
    fun `output stream pipe close() closes the underlying file stream`() {
        val cache = newCache()
        val pipe = cache.getOutputStreamPipe("key1")
        pipe.obtain()
        val os = pipe.open()
        os.write("hello".toByteArray())
        val fileStream = underlyingFile(os)
        assertTrue("stream should be open while writing", fileStream.channel.isOpen)
        pipe.close()
        pipe.release()
        assertFalse("pipe.close() must close the file stream", fileStream.channel.isOpen)
    }

    @Test
    fun `output stream pipe write is readable back after close`() {
        val cache = newCache()
        val pipe = cache.getOutputStreamPipe("key2")
        pipe.obtain()
        pipe.open().use { } // open then immediately hand back
        // Re-open within the same obtain session is forbidden; use a fresh pipe write.
        pipe.close()
        pipe.release()

        val writePipe = cache.getOutputStreamPipe("key3")
        writePipe.obtain()
        writePipe.open().write("payload".toByteArray())
        writePipe.close()
        writePipe.release()

        val readPipe = cache.getInputStreamPipe("key3")
        assertNotNull("committed entry must be readable (rename fails on Windows if fd left open)", readPipe)
        readPipe!!.obtain()
        val data = readPipe.open().readBytes()
        readPipe.close()
        readPipe.release()
        assertEquals("payload", String(data))
    }

    @Test
    fun `put() commits the entry and is readable back`() {
        val cache = newCache()
        val ok = cache.put("key4", ByteArrayInputStream("streamed".toByteArray()))
        assertTrue("put must succeed (rename fails on Windows if fd left open)", ok)

        val readPipe = cache.getInputStreamPipe("key4")
        assertNotNull(readPipe)
        readPipe!!.obtain()
        val data = readPipe.open().readBytes()
        readPipe.close()
        readPipe.release()
        assertEquals("streamed", String(data))
    }
}
