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

package com.lanraragi.reader.reading

import android.content.Context
import android.content.SharedPreferences
import com.lanraragi.framework.lib.glgallery.GalleryProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.DeviceMemory
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.diagnostics.DiagLog
import com.lanraragi.reader.domain.ReaderChapterList
import com.lanraragi.reader.storage.StorageFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * DiagLog tag of the reading-position trace (open, resolve, jump, saves). It
 * lands in "Share diagnostics", so a position that does not come back on a
 * user's device can be followed without a debug build.
 */
internal const val DIAG_TAG = "Progress"

abstract class GalleryProvider2 : GalleryProvider(DeviceMemory.readerCacheBytes()) {

    private val galleryViewAttached = CompletableDeferred<GalleryView>()

    /** Optional reference to GalleryView for async page navigation. */
    @Volatile
    var galleryView: GalleryView? = null
        set(value) {
            field = value
            if (value != null) galleryViewAttached.complete(value)
        }

    /** Pages to download ahead of the reader, for the pages on screen now (see the companion's pagesAhead). */
    protected fun pagesAhead(single: Int): Int =
        pagesAhead(single, galleryView?.currentSpreadParity ?: GalleryView.PARITY_UNKNOWN)

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

    // ==================== Lifecycle ====================

    /**
     * Where the provider's session stands, as one value. [size], [getError]
     * and every "has the reader closed" check read it; subclasses move it with
     * [markReady] / [markFailed], the base class with [start] / [stop].
     */
    sealed interface Lifecycle {
        /** What [size] reports in this state. */
        val size: Int

        /** Constructed, [start] not called yet. */
        data object Idle : Lifecycle {
            override val size: Int get() = STATE_WAIT
        }

        /** [start] ran; the page count is not known yet. */
        data object Starting : Lifecycle {
            override val size: Int get() = STATE_WAIT
        }

        /** [count] pages to show. A provider whose count changes (a tank's members) re-enters it. */
        data class Ready(val count: Int) : Lifecycle {
            override val size: Int get() = count
        }

        /** The session cannot show pages; [message] is the inline error. */
        data class Error(val message: String) : Lifecycle {
            override val size: Int get() = STATE_ERROR
        }

        /**
         * [stop] ran; terminal. Keeps the [size] and [error] the reader last
         * saw, so a frame drawn during teardown does not change what it shows.
         */
        data class Stopped(override val size: Int, val error: String? = null) : Lifecycle

        /** The state after [start]. */
        fun started(): Lifecycle = if (this == Idle) Starting else this

        /** The state once [count] pages are known; ignored before start and once failed or stopped. */
        fun ready(count: Int): Lifecycle = when (this) {
            Starting, is Ready -> Ready(count)
            else -> this
        }

        /** The state after a failure to load; ignored once failed or stopped. */
        fun failed(message: String): Lifecycle = when (this) {
            is Stopped, is Error -> this
            else -> Error(message)
        }

        /** The state after [stop]. */
        fun stopped(): Lifecycle = this as? Stopped ?: Stopped(size, (this as? Error)?.message)
    }

    private val lifecycleRef = AtomicReference<Lifecycle>(Lifecycle.Idle)

    /** The session's [Lifecycle]. */
    val lifecycle: Lifecycle get() = lifecycleRef.get()

    /** [stop] ran: background work must not request, publish or save anything more. */
    protected val isStopped: Boolean get() = lifecycleRef.get() is Lifecycle.Stopped

    /** [count] pages are known (again). Publish what they need first, then call this, then notifyDataChanged. */
    protected fun markReady(count: Int) {
        lifecycleRef.updateAndGet { it.ready(count) }
    }

    /** The session failed with the inline error [message]; call notifyDataChanged after it. */
    protected fun markFailed(message: String) {
        lifecycleRef.updateAndGet { it.failed(message) }
    }

    final override fun size(): Int = lifecycleRef.get().size

    final override fun getError(): String? = when (val state = lifecycleRef.get()) {
        is Lifecycle.Error -> state.message
        is Lifecycle.Stopped -> state.error
        else -> null
    }

    // ==================== Start position ====================

    private val restoreRef = AtomicReference(RestoreState())

    /** The start position and whether the restore may still move it (see [RestoreState]). */
    protected val restore: RestoreState get() = restoreRef.get()

    /** Applies [step] to [restore] atomically, from whichever thread. */
    protected fun updateRestore(step: (RestoreState) -> RestoreState): RestoreState = restoreRef.updateAndGet(step)

    /** Short name in this provider's DiagLog lines ("lrr", "dir", ...). */
    protected open val diagName: String get() = "reader"

    /**
     * Explicit start page from the launch intent (KEY_PAGE, e.g. a
     * thumbnail-grid tap), or -1 when the provider's own saved progress
     * decides. Providers use it ([RestoreState.entryPage]) to warm / consume
     * the decoded slot for the page the reader will actually land on,
     * instead of the SP-saved one (a thumbnail tap to a far page otherwise
     * always missed the warm and replayed the open fade-in).
     */
    var initialPageOverride: Int
        get() = restoreRef.get().override
        set(value) {
            updateRestore { it.copy(override = value) }
        }

    /** The reader reports every touch and key event here (Activity.onUserInteraction). */
    fun onUserInteraction() {
        updateRestore { it.interacted() }
    }

    override fun start() {
        super.start()
        lifecycleRef.updateAndGet { it.started() }
        updateRestore { it.started() }
    }

    private val chapterList = MutableStateFlow(ReaderChapterList())

    /** The session's chapters; empty (no entry point) unless the provider publishes some. */
    val chapters: StateFlow<ReaderChapterList> = chapterList.asStateFlow()

    protected fun publishChapters(list: ReaderChapterList) {
        chapterList.value = list
    }

    /** The chapter list was opened: a provider that loads chapters lazily starts now. */
    open fun requestChapters() {}

    /** The reading position: the saved page until the reader reports one, then the page on screen. */
    fun getStartPage(): Int = restoreRef.get().startPage

    // ==================== Progress ====================

    /** Where a read page is reported beyond the local save. */
    sealed interface ProgressTarget {
        /** A plain archive: the server's archive progress, queued in [ArchiveProgressOutbox] while it fails. */
        data class Archive(val arcid: String) : ProgressTarget

        /** A tank read as one book: the server's tank progress; [TankProgress] holds the page not yet sent. */
        data class Tank(val context: Context, val tankId: String) : ProgressTarget
    }

    /** Where this session's pages are reported; null = local only. First read on a page report, after construction. */
    protected open val progressTarget: ProgressTarget? get() = null

    /** The base URL to report progress to (null: none, the report is dropped). Called off the main thread. */
    protected open suspend fun progressServerUrl(): String? = null

    /** Serialized + conflated server progress sync (app-scoped, survives [stop]). */
    private val progressSyncer by lazy {
        ReadingProgressSyncer(ServiceRegistry.coroutineModule.ioScope, ::sendProgress)
    }

    private suspend fun sendProgress(page0: Int) {
        val target = progressTarget ?: return
        val url = progressServerUrl() ?: return
        val client = ServiceRegistry.networkModule.okHttpClient
        when (target) {
            is ProgressTarget.Archive -> ArchiveProgressOutbox.tracked(url, target.arcid, page0) {
                LRRArchiveApi.updateProgress(client, url, target.arcid, page0 + 1)
            }
            is ProgressTarget.Tank -> {
                LRRTankoubonApi.updateTankProgress(client, url, target.tankId, page0 + 1)
                TankProgress.markSynced(target.context, target.tankId, page0)
            }
        }
    }

    /**
     * The reader shows [page0] (0-indexed); the single entry for a read page.
     * It updates the start position (and decides whether this was the user's
     * navigation), saves the page locally within a second, and reports it to
     * the [progressTarget], serialized and conflated so the last page of a
     * fast-flip burst is what the server ends up with.
     */
    fun onPageRead(page0: Int) {
        val before = restoreRef.getAndUpdate { it.pageShown(page0) }
        if (page0 != before.baseline && !before.userNavigated) {
            DiagLog.i(
                DIAG_TAG,
                if (before.userInteracted) {
                    "$diagName navigated page=$page0 baseline=${before.baseline}"
                } else {
                    "$diagName index $page0 without user input (baseline=${before.baseline})"
                },
            )
        }
        saveLocalProgressSoon(page0)
        val target = progressTarget ?: return
        if (target is ProgressTarget.Tank) TankProgress.markPending(target.context, target.tankId, page0)
        progressSyncer.submit(page0)
    }

    /** Re-sends [page0], a page an earlier session read but could not report. */
    protected fun resendProgress(page0: Int) = progressSyncer.submit(page0)

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
    private fun saveLocalProgressSoon(page: Int) = deferredLocalSave.submit(page)

    /** Writes a recorded local position now: the reader is pausing or the provider stopping. */
    fun flushLocalProgress() = deferredLocalSave.flush()

    override fun stop() {
        flushLocalProgress()
        super.stop()
        lifecycleRef.updateAndGet { it.stopped() }
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

        /**
         * Pages to download ahead: [single] for single pages, twice as many
         * while two-page spreads are on screen ([spreadParity] known), since
         * each turn moves two pages on. Downloaded bytes only; decoding is
         * unchanged.
         */
        internal fun pagesAhead(single: Int, spreadParity: Int): Int =
            if (spreadParity == GalleryView.PARITY_UNKNOWN) single else single * 2

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
