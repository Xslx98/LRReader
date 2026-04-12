package com.hippo.ehviewer.util

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object GZIPUtils {

    /**
     * 使用gzip进行压缩
     */
    @JvmStatic
    fun compress(primStr: String?): String? {
        if (primStr.isNullOrEmpty()) {
            return primStr
        }

        val out = ByteArrayOutputStream()
        var gzip: GZIPOutputStream? = null
        try {
            gzip = GZIPOutputStream(out)
            gzip.write(primStr.toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (gzip != null) {
                try {
                    gzip.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        return String(Base64.encode(out.toByteArray(), Base64.DEFAULT))
    }

    @JvmStatic
    fun uncompress(compressedStr: String?): String? {
        if (compressedStr == null) {
            return null
        }

        val out = ByteArrayOutputStream()
        var inStream: ByteArrayInputStream? = null
        var ginzip: GZIPInputStream? = null
        var decompressed: String? = null
        try {
            val compressed = Base64.decode(compressedStr.toByteArray(), Base64.DEFAULT)
            inStream = ByteArrayInputStream(compressed)
            ginzip = GZIPInputStream(inStream)

            val buffer = ByteArray(1024)
            var offset: Int
            while (ginzip.read(buffer).also { offset = it } != -1) {
                out.write(buffer, 0, offset)
            }
            decompressed = out.toString()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try { ginzip?.close() } catch (_: IOException) {}
            try { inStream?.close() } catch (_: IOException) {}
            try { out.close() } catch (_: IOException) {}
        }
        return decompressed
    }
}
