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
import android.content.SharedPreferences
import androidx.core.content.edit
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.glgallery.GalleryView
import com.hippo.unifile.UniFile

abstract class GalleryProvider2 : GalleryProvider() {

    /** Optional reference to GalleryView for async page navigation. */
    @Volatile
    var galleryView: GalleryView? = null

    /**
     * Explicit start page from the launch intent (KEY_PAGE, e.g. a
     * thumbnail-grid tap), or -1 when the provider's own saved progress
     * decides. Providers use it to warm / consume the decoded slot for the
     * page the reader will actually land on, instead of the SP-saved one
     * (a thumbnail tap to a far page otherwise always missed the warm and
     * replayed the open fade-in).
     */
    @Volatile
    var initialPageOverride: Int = -1

    open fun getStartPage(): Int = 0

    open fun putStartPage(page: Int) {}

    /**
     * Persist the intra-page scroll fraction (0.0 ~ 1.0) for this
     * archive. Local-only: no server sync. Default no-op for
     * providers that don't track per-archive history.
     */
    open fun putScrollFraction(fraction: Float) {}

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
        // Single source of truth for "is this a local page file", shared by
        // routing (GalleryOpenHelper.hasImageFiles) and the reader's dir
        // lister (DirImageFiles). Keep it a subset
        // of what the download worker writes AND what the system decoder reads
        // (Image.kt: ImageDecoder/BitmapFactory). Extensions include the dot.
        //
        // .jxl is intentionally absent: the platform ships no JPEG XL decoder
        // at any API level (minSdk 28), so listing it would surface
        // undecodable pages instead of a clean fallback.
        @JvmField
        val SUPPORT_IMAGE_EXTENSIONS = arrayOf(
            ".jpg",  // Joint Photographic Experts Group
            ".jpeg",
            ".png",  // Portable Network Graphics
            ".gif",  // Graphics Interchange Format
            ".webp",
            ".bmp",  // Bitmap — decoded on every API level
            ".avif", // AV1 Image File Format — system decoder on API 31+
            ".heif", // High Efficiency Image Format — API 28+ (= minSdk)
            ".heic"  // HEVC-coded HEIF — API 28+ (= minSdk)
        )

        /** SharedPreferences name for local reading progress storage. */
        private const val SP_READING_PROGRESS = "reading_progress"

        /** Key suffix for the per-arcid save timestamp (epoch seconds). */
        private const val TS_SUFFIX = "_ts"

        /**
         * Save reading progress locally (0-indexed page number) with timestamp.
         * @param arcid Archive identifier (used as SP key)
         * @param page 0-indexed current page
         */
        @JvmStatic
        fun saveReadingProgress(ctx: Context, arcid: String, page: Int) {
            val prefs = ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
            prefs.edit {
                putInt(arcid, page)
                putLong("${arcid}_ts", System.currentTimeMillis() / 1000L)
            }
            ReadingProgressTracker.setProgress(arcid, page)
            maybeTrimReadingProgress(prefs, arcid)
        }

        /** Archive-count cap for the reading_progress store (2 keys each). */
        internal const val MAX_PROGRESS_ENTRIES = 500

        /** Entries kept after a trim (hysteresis so trims stay rare). */
        internal const val TRIM_TARGET = 400

        /**
         * Bound the reading_progress store: it used to grow by two keys per
         * archive ever opened, forever — and SharedPreferences rewrites the
         * WHOLE XML file on every page turn, so the per-save cost grew with
         * lifetime library usage. Above [MAX_PROGRESS_ENTRIES] archives the
         * oldest entries (by `_ts`; legacy entries without one count as
         * oldest) are pruned down to [TRIM_TARGET]. [activeArcid] — the
         * archive being read right now — is never pruned.
         */
        @JvmStatic
        internal fun maybeTrimReadingProgress(prefs: SharedPreferences, activeArcid: String) {
            val all = prefs.all
            val arcids = all.keys.filter { !it.endsWith(TS_SUFFIX) }
            if (arcids.size <= MAX_PROGRESS_ENTRIES) return
            val toRemove = arcids
                .sortedBy { (all[it + TS_SUFFIX] as? Long) ?: 0L }
                .take(arcids.size - TRIM_TARGET)
                .filter { it != activeArcid }
            prefs.edit {
                for (key in toRemove) {
                    remove(key)
                    remove(key + TS_SUFFIX)
                }
            }
        }

        /**
         * Load reading progress from local storage.
         * @param arcid Archive identifier (used as SP key)
         * @return 0-indexed page number, or 0 if not found
         */
        @JvmStatic
        fun loadReadingProgress(ctx: Context, arcid: String): Int {
            return ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getInt(arcid, 0)
        }

        /**
         * Load the timestamp (epoch seconds) of the last local progress save.
         * @param arcid Archive identifier (used as SP key)
         * @return epoch seconds, or 0 if not found
         */
        @JvmStatic
        fun loadReadingTimestamp(ctx: Context, arcid: String): Long {
            return ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getLong("${arcid}_ts", 0L)
        }

        /**
         * Remove the local progress save for [arcid] — both the page key and
         * the timestamp key (a surviving `_ts` would make page 0 look like a
         * real save) — and push the no-progress sentinel into
         * [ReadingProgressTracker] so detail-page observers refresh.
         * Part of the "reset reading progress" flow.
         */
        @JvmStatic
        fun clearReadingProgress(ctx: Context, arcid: String) {
            ctx.applicationContext
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .edit {
                    remove(arcid)
                    remove("${arcid}_ts")
                }
            ReadingProgressTracker.setProgress(arcid, ReadingProgressTracker.NO_LOCAL_PROGRESS)
        }
    }
}
