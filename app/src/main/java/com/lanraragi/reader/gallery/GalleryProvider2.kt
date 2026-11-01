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

package com.lanraragi.reader.gallery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lanraragi.framework.lib.glgallery.GalleryProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.diagnostics.DiagLog
import com.lanraragi.reader.storage.StorageFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DiagLog tag of the reading-position trace (open, resolve, jump, saves). It
 * lands in "Share diagnostics", so a position that does not come back on a
 * user's device can be followed without a debug build.
 */
internal const val DIAG_TAG = "Progress"

abstract class GalleryProvider2 : GalleryProvider() {

    private val galleryViewAttached = CompletableDeferred<GalleryView>()

    /** Optional reference to GalleryView for async page navigation. */
    @Volatile
    var galleryView: GalleryView? = null
        set(value) {
            field = value
            if (value != null) galleryViewAttached.complete(value)
        }

    /**
     * The [galleryView], waiting up to [timeoutMs] for the reader to attach
     * it. GalleryActivity starts the provider before it builds the view, so a
     * fast start (warm page cache, LAN server) reaches its start-position
     * jump first; reading the field then skipped the jump and lost the saved
     * page and scroll fraction.
     */
    @Suppress("RedundantSuspendModifier") // false positive: detekt does not resolve withTimeoutOrNull as suspend
    protected suspend fun awaitGalleryView(timeoutMs: Long = GALLERY_VIEW_ATTACH_TIMEOUT_MS): GalleryView? =
        galleryView ?: withTimeoutOrNull(timeoutMs) { galleryViewAttached.await() }

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

    @Volatile
    private var userInteracted = false

    /**
     * Whether the user has touched the reader or pressed a key since [start].
     * Only then can a current-index change be their navigation: when the
     * reader opens, the layout can settle through other pages first (a
     * device reported page 0 for a moment before the saved page), and
     * taking that echo for navigation cancelled the start-position restore
     * and saved page 0 over the reading position.
     */
    protected val hasUserInteracted: Boolean get() = userInteracted

    /** The reader reports every touch and key event here (Activity.onUserInteraction). */
    fun onUserInteraction() {
        userInteracted = true
    }

    /**
     * Whether the reader opened on an explicit page (a thumbnail tap) other
     * than [savedStartPage]: then the user chose where to be and the
     * saved-position restore must not move them.
     */
    protected fun openedOnOtherPage(savedStartPage: Int): Boolean =
        initialPageOverride >= 0 && initialPageOverride != savedStartPage

    override fun start() {
        super.start()
        userInteracted = false
    }

    open fun getStartPage(): Int = 0

    open fun putStartPage(page: Int) {}

    /**
     * Persist the intra-page scroll fraction (0.0 ~ 1.0) for this
     * archive. Local-only: no server sync. Default no-op for
     * providers that don't track per-archive history.
     */
    open fun putScrollFraction(fraction: Float) {}

    private val deferredLocalSave = DeferredProgressSave { page ->
        DiagLog.i(DIAG_TAG, "save page=$page")
        writeLocalProgress(page)
    }

    /** Writes the local reading position [page]; [saveLocalProgressSoon] calls it at most once a second. */
    protected open fun writeLocalProgress(page: Int) {}

    /** Records [page] as the local reading position, written within a second (audit 2026-10-13 PERF-10). */
    protected fun saveLocalProgressSoon(page: Int) = deferredLocalSave.submit(page)

    /** Writes a recorded local position now: the reader is pausing or the provider stopping. */
    fun flushLocalProgress() = deferredLocalSave.flush()

    override fun stop() {
        flushLocalProgress()
        super.stop()
    }

    /**
     * @return without extension
     */
    abstract fun getImageFilename(index: Int): String

    abstract fun save(index: Int, file: StorageFile): Boolean

    /**
     * @param filename without extension
     */
    abstract fun save(index: Int, dir: StorageFile, filename: String): StorageFile?

    companion object {
        /** How long a start-position jump waits for the reader to attach its GalleryView. */
        private const val GALLERY_VIEW_ATTACH_TIMEOUT_MS = 5_000L

        // Single source of truth for "is this a local page file", shared by
        // routing (DownloadDirResolver.hasImageFiles) and the reader's dir
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
        internal const val SP_READING_PROGRESS = LocalReadingProgress.PREFS

        /**
         * Save reading progress locally (0-indexed page number) with timestamp,
         * per server profile ([LocalReadingProgress], audit C37).
         * @param arcid Archive identifier
         * @param page 0-indexed current page
         * @param profileId the archive's source profile
         *   ([LocalReadingProgress.sourceProfile]); defaults to the active one
         */
        @JvmStatic
        fun saveReadingProgress(
            ctx: Context,
            arcid: String,
            page: Int,
            profileId: Long = LocalReadingProgress.profileId(),
        ) {
            LocalReadingProgress.save(ctx, arcid, page, System.currentTimeMillis() / 1000L, profileId)
            ReadingProgressTracker.setProgress(profileId, arcid, page)
        }

        /** Archive-count cap for the reading_progress store (2 keys each). */
        internal const val MAX_PROGRESS_ENTRIES = LocalReadingProgress.MAX_ENTRIES

        /** Entries kept after a trim (hysteresis so trims stay rare). */
        internal const val TRIM_TARGET = LocalReadingProgress.TRIM_TARGET

        /** See [LocalReadingProgress.trim]; [activeKey] is never pruned. */
        @JvmStatic
        internal fun maybeTrimReadingProgress(prefs: SharedPreferences, activeKey: String) =
            LocalReadingProgress.trim(prefs, activeKey)

        /**
         * Load reading progress from local storage for [profileId] (default:
         * the active server profile; see [LocalReadingProgress.load]).
         * @return 0-indexed page number, or 0 if not found
         */
        @JvmStatic
        fun loadReadingProgress(
            ctx: Context,
            arcid: String,
            profileId: Long = LocalReadingProgress.profileId(),
        ): Int = LocalReadingProgress.load(ctx, arcid, profileId)

        /**
         * Load the timestamp (epoch seconds) of the last local progress save.
         * @return epoch seconds, or 0 if not found
         */
        @JvmStatic
        fun loadReadingTimestamp(
            ctx: Context,
            arcid: String,
            profileId: Long = LocalReadingProgress.profileId(),
        ): Long = LocalReadingProgress.loadTimestamp(ctx, arcid, profileId)

        /**
         * Remove the local progress save for [arcid] — both the page key and
         * the timestamp key (a surviving `_ts` would make page 0 look like a
         * real save) — and push the no-progress sentinel into
         * [ReadingProgressTracker] so detail-page observers refresh.
         * Part of the "reset reading progress" flow, which passes the
         * download's own source [profileId] (audit 2026-10-06 C37).
         */
        @JvmStatic
        fun clearReadingProgress(ctx: Context, arcid: String, profileId: Long = LocalReadingProgress.profileId()) {
            LocalReadingProgress.clear(ctx, arcid, profileId)
            ReadingProgressTracker.clearProgress(profileId, arcid)
        }
    }
}
