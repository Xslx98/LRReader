package com.lanraragi.reader.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.gallery.DownloadDirResolver
import com.lanraragi.reader.gallery.ReaderPageCache
import com.lanraragi.reader.gallery.ReadingProgressReconciler
import com.lanraragi.reader.settings.DownloadSettings
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import java.io.File

/**
 * Shared utility for building the optimal Intent to open a gallery for reading.
 *
 * If local downloaded files exist for the given archive, opens with [GalleryActivity.ACTION_DIR]
 * (instant, offline). Otherwise falls back to [GalleryActivity.ACTION_LRR] (server streaming);
 * a partial local copy with network up streams in hybrid mode, reading and
 * filling the download directory ([GalleryActivity.KEY_DOWNLOAD_DIR]).
 */
object GalleryOpenHelper {

    private const val TAG = "GalleryOpenHelper"

    /**
     * Build an Intent for reading the given archive, preferring local files if available.
     *
     * @param context Context
     * @param archive Archive to open
     * @param startPage 0-indexed start page; `-1` (default) means "use whatever
     *   the GalleryProvider would default to" — typically the last-read
     *   progress. Pass an explicit page to jump straight there (e.g. from a
     *   thumbnail-grid tap on the detail page).
     * @return Intent ready for startActivity()
     */
    /**
     * @param knownComplete authoritative local-completeness from the caller,
     *   overriding the [archive.pagecount] heuristic. The downloads list
     *   passes `state == FINISH` here: a [DownloadInfo] reaches the reader
     *   via the lossy `toArchive()` mapper, which zeroes `pagecount`, so the
     *   count heuristic alone would treat every partial download as
     *   "complete" (unknown pagecount). `null` (detail page / history / list)
     *   falls back to the pagecount heuristic.
     */
    @JvmStatic
    suspend fun buildReadIntent(
        context: Context,
        archive: Archive,
        startPage: Int = -1,
        knownComplete: Boolean? = null,
    ): Intent = buildReadIntentInternal(
        context, enrichProgressSnapshot(archive), startPage, knownComplete
    )

    /**
     * The downloads/history scenes reach [buildReadIntent] through lossy
     * view mappers (`DownloadInfo`/`HistoryInfo.toArchive()`) that
     * deliberately zero `progress` (the history variant carries only its
     * own read-open time, not the server progress pair) — those fields
     * round-trip via detail fetches. The warm target and the dir
     * provider's start-page seed both need the Room snapshot, so when the
     * caller's Archive carries no progress, overlay the pair from the
     * persisted `archive_json` row. Progress and lastreadtime move
     * together: the reconciler needs them from the same source.
     */
    private suspend fun enrichProgressSnapshot(archive: Archive): Archive {
        if (archive.progress > 0) return archive
        val snapshot = runCatching {
            ServiceRegistry.dataModule.historyRepository
                .getArchiveSnapshot(archive.arcid, archive.serverProfileId)
        }.getOrNull() ?: return archive
        if (snapshot.progress <= 0) return archive
        return archive.copy(
            progress = snapshot.progress,
            lastreadtime = snapshot.lastreadtime,
        )
    }

    /**
     * Intent for a WHOLE-TANK composite session ([GalleryActivity.ACTION_TANK]).
     * [startGlobalPage] is the tank-global 0-indexed page to open on; -1 lets
     * [com.lanraragi.reader.gallery.TankGalleryProvider] restore its own saved
     * progress (local tank save, else the seed's server progress captured by
     * the caller into [startGlobalPage]).
     */
    @JvmStatic
    fun buildTankReadIntent(
        context: Context,
        seed: com.lanraragi.reader.gallery.TankSessionSeed,
        startGlobalPage: Int = -1,
    ): Intent = Intent(context, GalleryActivity::class.java).apply {
        action = GalleryActivity.ACTION_TANK
        putExtra(GalleryActivity.KEY_TANK_SEED, seed)
        if (startGlobalPage >= 0) putExtra(GalleryActivity.KEY_PAGE, startGlobalPage)
    }

    private suspend fun buildReadIntentInternal(
        context: Context,
        archive: Archive,
        startPage: Int,
        knownComplete: Boolean?,
    ): Intent {
        val intent = Intent(context, GalleryActivity::class.java)

        // Check if local downloaded files exist AND are complete. A partial
        // copy (download paused / failed / in progress) routes to streaming
        // so the page sequence is whole and correctly ordered; the partial
        // dir is only used when there is no network to stream from.
        val downloadDir = getLocalDownloadDir(context, archive)
        val localComplete = downloadDir != null &&
            (knownComplete ?: DownloadDirResolver.isLocalCopyComplete(downloadDir, archive.pagecount))
        val networkAvailable = ServiceRegistry.networkModule.networkMonitor.isAvailable
        if (downloadDir != null && (localComplete || !networkAvailable)) {
            if (!localComplete && BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "[ROUTE] incomplete local copy for arcid=${archive.arcid}" +
                        " opened offline (no network)"
                )
            }
            // Local files available — read offline (instant)
            intent.action = GalleryActivity.ACTION_DIR
            intent.putExtra(GalleryActivity.KEY_FILENAME, downloadDir.absolutePath)
            intent.putExtra(GalleryActivity.KEY_ARCHIVE, archive)
            // Fire-and-forget Dir warmup so DirGalleryProvider.start()'s
            // consumeDecodedPage call has a chance of hitting before the
            // user sees the loading placeholder.
            UniFile.fromFile(downloadDir)?.let { uniFile ->
                if (BuildConfig.DEBUG) Log.i(TAG, "[WARM] openHelper DIR trigger arcid=${archive.arcid}")
                // Explicit start page (thumbnail tap) wins; otherwise the
                // offline reconcile — the SAME math and inputs that seed
                // DirGalleryProvider's start page, so the warm target and the
                // provider's slot-consume index agree and the hand-off hits.
                val warmPage = if (startPage >= 0) {
                    startPage
                } else {
                    ReadingProgressReconciler.resolveOffline(
                        context, archive.arcid, archive.progress, archive.lastreadtime
                    )
                }
                ReaderPageCache.warmDir(context, archive.arcid, uniFile, warmPage)
            }
        } else {
            // No local files, or an incomplete local copy with network up —
            // stream from LANraragi server. A partial local copy puts the
            // streaming provider in hybrid mode: it reads the pages already
            // on disk and writes the ones it fetches into the same directory
            // (see LRRGalleryProvider), whatever the download's current state.
            if (downloadDir != null && BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "[ROUTE] incomplete local copy for arcid=${archive.arcid}" +
                        " (${DownloadDirResolver.countImageFiles(downloadDir)}/${archive.pagecount})," +
                        " streaming from server in hybrid mode"
                )
            }
            intent.action = GalleryActivity.ACTION_LRR
            intent.putExtra(GalleryActivity.KEY_ARCHIVE, archive)
            // A tracked download whose directory has no pages yet (tapped
            // READ right after DOWNLOAD, or a download that failed before
            // its first page) is still a hybrid session: the provider
            // creates the directory and both sides fill it.
            val hybridDir = downloadDir ?: DownloadDirResolver.pendingDownloadDir(archive)
            if (hybridDir != null) {
                intent.putExtra(GalleryActivity.KEY_DOWNLOAD_DIR, hybridDir.absolutePath)
            }
            // Fire-and-forget LRR warmup. preloadForDetail downloads the
            // bytes and decode-warms the slot. Idempotent w.r.t. an
            // earlier detail-page trigger; the slot's
            // store-replaces-and-recycles semantics handle a duplicate.
            // Resolve the archive's source profile (not the active one) so
            // the warm hits the same server the reader will stream from.
            val serverUrl = runCatching {
                resolveSourceBaseUrl(
                    archive.serverProfileId,
                    ServiceRegistry.dataModule.profileLookupCache,
                )
            }.getOrNull()
            if (serverUrl != null) {
                // Warmup the slot the user will actually land on: an
                // explicit startPage overrides the saved progress so
                // tapping a thumbnail decodes that exact page next.
                // Otherwise reconcile SP vs the Room snapshot — the raw
                // `progress - 1` ignored a further-along local save and
                // warmed a page the reader would never open on.
                val warmupPage = if (startPage >= 0) {
                    startPage
                } else {
                    ReadingProgressReconciler.resolveOffline(
                        context, archive.arcid, archive.progress, archive.lastreadtime
                    )
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "[WARM] openHelper LRR trigger arcid=${archive.arcid} page=$warmupPage")
                ReaderPageCache.preloadForDetail(context, archive.arcid, serverUrl, warmupPage)
            }
        }

        // Override the reader's default start page when the caller knows where
        // to land (e.g. the detail page's thumbnail grid). Negative values fall
        // through to GalleryProvider2.getStartPage().
        if (startPage >= 0) {
            intent.putExtra(GalleryActivity.KEY_PAGE, startPage)
        }

        return intent
    }

    /** See [DownloadDirResolver.localDownloadDir]; kept here for the existing call sites. */
    @JvmStatic
    suspend fun getLocalDownloadDir(context: Context, archive: Archive): File? =
        DownloadDirResolver.localDownloadDir(context, archive)
}
