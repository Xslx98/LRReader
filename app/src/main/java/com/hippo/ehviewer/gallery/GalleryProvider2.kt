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
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.glgallery.GalleryView
import com.hippo.unifile.UniFile

abstract class GalleryProvider2 : GalleryProvider() {

    /** Optional reference to GalleryView for async page navigation. */
    @Volatile
    var galleryView: GalleryView? = null

    open fun getStartPage(): Int = 0

    open fun putStartPage(page: Int) {}

    /**
     * @return without extension
     */
    abstract fun getImageFilename(index: Int): String

    abstract fun save(index: Int, file: UniFile): Boolean

    /**
     * @param filename without extension
     */
    abstract fun save(index: Int, dir: UniFile, filename: String): UniFile?

    companion object {
        // With dot
        @JvmField
        val SUPPORT_IMAGE_EXTENSIONS = arrayOf(
            ".jpg",  // Joint Photographic Experts Group
            ".jpeg",
            ".png",  // Portable Network Graphics
            ".gif",  // Graphics Interchange Format
            ".webp"
        )

        /** SharedPreferences name for local reading progress storage. */
        private const val SP_READING_PROGRESS = "reading_progress"

        /**
         * Save reading progress locally (0-indexed page number) with timestamp.
         * @param gid Gallery identifier (used as SP key)
         * @param page 0-indexed current page
         */
        @JvmStatic
        fun saveReadingProgress(ctx: Context, gid: Long, page: Int) {
            ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .edit()
                .putInt(gid.toString(), page)
                .putLong("${gid}_ts", System.currentTimeMillis() / 1000L)
                .apply()
        }

        /**
         * Load reading progress from local storage.
         * @return 0-indexed page number, or 0 if not found
         */
        @JvmStatic
        fun loadReadingProgress(ctx: Context, gid: Long): Int {
            return ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getInt(gid.toString(), 0)
        }

        /**
         * Load the timestamp (epoch seconds) of the last local progress save.
         * @return epoch seconds, or 0 if not found
         */
        @JvmStatic
        fun loadReadingTimestamp(ctx: Context, gid: Long): Long {
            return ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getLong("${gid}_ts", 0L)
        }
    }
}
