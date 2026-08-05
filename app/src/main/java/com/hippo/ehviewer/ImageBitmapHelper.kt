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

class ImageBitmapHelper(
    private val maxCachePixels: Int = DEFAULT_MAX_CACHE_PIXELS,
) : ValueHelper<Image> {

    companion object {
        // Thumbnails are portrait (~2:3); a ~1000px server thumb decoded under
        // the 128x192dp detail-header floor lands at ~500x750, which the old
        // square 512*512 cap rejected — so scrolled thumbs never entered the
        // memory cache and every re-bind decoded from disk. This fixed value
        // is only the fallback: it equals the decode floor at 640 dpi, so on
        // high-density devices decodes bounded by the floor could EXCEED it
        // and silently bypass the cache again. Production injects
        // [cacheCapForFloor] with the real floor pixels instead.
        const val DEFAULT_MAX_CACHE_PIXELS = 512 * 768

        /**
         * Density-aware admission cap derived from the decode floor
         * (the 128x192dp detail-header thumb size in px). Integer
         * sample-size granularity means a floor-bounded decode can land
         * anywhere up to just-under 2x the floor per dimension — admit
         * that whole envelope.
         */
        fun cacheCapForFloor(floorWidthPx: Int, floorHeightPx: Int): Int =
            (floorWidthPx * 2) * (floorHeightPx * 2)
    }

    override fun decode(isPipe: InputStreamPipe): Image? {
        return decode(isPipe, true)
    }

    override fun decode(isPipe: InputStreamPipe, hardware: Boolean): Image? {
        return decode(isPipe, hardware, 0, 0)
    }

    override fun decode(
        isPipe: InputStreamPipe,
        hardware: Boolean,
        targetWidth: Int,
        targetHeight: Int
    ): Image? {
        return try {
            isPipe.obtain()
            val inputStream = isPipe.open()
            if (inputStream is FileInputStream) {
                Image.decode(inputStream, hardware, targetWidth, targetHeight)
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
                        Image.decode(fis, hardware, targetWidth, targetHeight)
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
            value.width * value.height <= maxCachePixels
        } else {
            true
        }
    }
}
