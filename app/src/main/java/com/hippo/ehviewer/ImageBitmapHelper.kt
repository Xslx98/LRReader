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

import com.hippo.conaco.ValueHelper
import com.hippo.lib.image.Image
import com.hippo.streampipe.InputStreamPipe
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class ImageBitmapHelper : ValueHelper<Image> {

    companion object {
        private const val MAX_CACHE_SIZE = 512 * 512
    }

    override fun decode(isPipe: InputStreamPipe): Image? {
        return decode(isPipe, true)
    }

    override fun decode(isPipe: InputStreamPipe, hardware: Boolean): Image? {
        return try {
            isPipe.obtain()
            val inputStream = isPipe.open()
            if (inputStream is FileInputStream) {
                Image.decode(inputStream, hardware)
            } else {
                // Non-FileInputStream (e.g., SAF content:// URI) — copy to temp file
                val tmpFile = File.createTempFile("ibh_", ".tmp")
                try {
                    inputStream.use { inp ->
                        FileOutputStream(tmpFile).use { fos ->
                            inp.copyTo(fos)
                        }
                    }
                    FileInputStream(tmpFile).use { fis ->
                        Image.decode(fis, hardware)
                    }
                } finally {
                    tmpFile.delete()
                }
            }
        } catch (e: OutOfMemoryError) {
            Analytics.recordException(e)
            null
        } catch (e: IOException) {
            null
        } finally {
            isPipe.close()
            isPipe.release()
        }
    }

    override fun sizeOf(key: String, value: Image): Int {
        return value.width * value.height * 4 /* value.getByteCount() TODO Update Image */
    }

    override fun onAddToMemoryCache(oldValue: Image) {
        oldValue.obtain()
    }

    override fun onRemoveFromMemoryCache(key: String, oldValue: Image) {
        oldValue.release()
    }

    override fun useMemoryCache(key: String, value: Image?): Boolean {
        return if (value != null) {
            value.width * value.height <= MAX_CACHE_SIZE
            /* value.getByteCount() <= MAX_CACHE_BYTE_COUNT TODO Update Image */
        } else {
            true
        }
    }
}
