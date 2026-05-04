/*
 * Copyright 2026 LR Reader contributors
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
 */

package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Bitmap helpers shared across UI surfaces (avatars, backgrounds, thumbnails).
 *
 * Centralised here so that every BitmapFactory.decode site uses the same
 * power-of-two sub-sampling strategy and avoids OOM on large user-supplied
 * images. A 4 MB JPEG decoded at full resolution produces a ~30–50 MB
 * ARGB_8888 bitmap; sampling brings it down by 1/N² in each dimension.
 */
object ImageDecodeUtils {

    /** Default target dimension for in-app avatar / background usage. */
    const val DEFAULT_MAX_DIMENSION: Int = 1024

    /**
     * Decode a file into a [Bitmap] with [BitmapFactory.Options.inSampleSize]
     * picked so that neither dimension exceeds [maxDim]. Returns `null` if the
     * file is unreadable or not a recognisable image.
     *
     * Two-pass implementation:
     * 1. Decode bounds only (`inJustDecodeBounds = true`) to read width/height
     *    without allocating pixel memory.
     * 2. Pick the smallest power-of-two sample size such that the resulting
     *    bitmap fits within [maxDim] × [maxDim], then decode for real.
     */
    @JvmStatic
    @JvmOverloads
    fun decodeSampledBitmap(path: String, maxDim: Int = DEFAULT_MAX_DIMENSION): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeFile(path, options)
    }
}
