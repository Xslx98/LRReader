package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.gallery.ReaderPageCache
import com.hippo.ehviewer.gallery.ReadingProgressReconciler
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.unifile.UniFile
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import java.io.File

/**
 * Shared utility for building the optimal Intent to open a gallery for reading.
 *
 * If local downloaded files exist for the given archive, opens with [GalleryActivity.ACTION_DIR]
 * (instant, offline). Otherwise falls back to [GalleryActivity.ACTION_LRR] (server streaming).
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
    ): Intent {
        val intent = Intent(context, GalleryActivity::class.java)

        // Check if local downloaded files exist AND are complete. A partial
        // copy (download paused / failed / in progress) routes to streaming
        // so the page sequence is whole and correctly ordered; the partial
        // dir is only used when there is no network to stream from.
        val downloadDir = getLocalDownloadDir(context, archive)
        val localComplete = downloadDir != null &&
            (knownComplete ?: isLocalCopyComplete(downloadDir, archive.pagecount))
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
            // stream from LANraragi server.
            if (downloadDir != null && BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "[ROUTE] incomplete local copy for arcid=${archive.arcid}" +
                        " (${countImageFiles(downloadDir)}/${archive.pagecount})," +
                        " streaming from server instead"
                )
            }
            intent.action = GalleryActivity.ACTION_LRR
            intent.putExtra(GalleryActivity.KEY_ARCHIVE, archive)
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

    /**
     * Resolve the local download directory for an archive, or null if the
     * archive isn't readable offline. A non-null result is guaranteed to be
     * an existing `file://` directory that actually contains page images, so
     * callers can route straight to [GalleryActivity.ACTION_DIR].
     *
     * Resolution is layered so a stale persisted pointer can't silently send
     * a fully-downloaded archive to network streaming (the bug this guards):
     *  1. Primary — [SpiderDen.getGalleryDownloadDir] maps arcid → the DB
     *     `dirname` under the recorded root. Fast path; hits for almost every
     *     archive.
     *  2. Recovery — when the primary path is missing or empty, re-find the
     *     folder by its `arcid-` prefix under the current download root (the
     *     same naming [SpiderDen] creates) and repair the DB pointer so the
     *     primary path succeeds next time. Covers a dirname that drifted from
     *     the real folder (sanitisation change, title edit, aborted move,
     *     legacy row).
     *  3. Legacy — the pre-W34 app-private, title-named folder.
     */
    @JvmStatic
    suspend fun getLocalDownloadDir(context: Context, archive: Archive): File? {
        // 1. Primary resolution.
        fileDirFromUni(SpiderDen.getGalleryDownloadDir(archive.arcid, archive.title))
            ?.let { primary -> if (hasImageFiles(primary)) return primary }

        // 2. Defensive recovery by arcid prefix under the current root.
        fileDirFromUni(DownloadSettings.getDownloadLocation())?.let { root ->
            val recovered = root.listFiles()?.firstOrNull { f ->
                f.isDirectory && f.name.startsWith("${archive.arcid}-") && hasImageFiles(f)
            }
            if (recovered != null) {
                if (BuildConfig.DEBUG) {
                    Log.w(
                        TAG,
                        "[DIR-RESCUE] primary resolution missed arcid=${archive.arcid}; " +
                            "recovered '${recovered.name}' by prefix scan"
                    )
                }
                // Self-heal the DB pointer so the fast path hits next time
                // (and so the download worker doesn't orphan into a new dir).
                runCatching {
                    ServiceRegistry.dataModule.downloadDbRepository
                        .putDownloadDirname(archive.arcid, recovered.name)
                }
                return recovered
            }
        }

        // 3. Legacy app-private fallback.
        val title = archive.title.takeIf { it.isNotEmpty() } ?: return null
        val baseDir = File(context.getExternalFilesDir(null), "download")
        val dirName = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        val oldDir = File(baseDir, dirName)
        return if (oldDir.isDirectory && hasImageFiles(oldDir)) oldDir else null
    }

    /**
     * Map a [UniFile] to a [File] only when it is a `file://` directory.
     * Returns null for content:// (SAF) trees — those can't be handed to
     * [GalleryActivity.ACTION_DIR], which expects a filesystem path — and for
     * non-existent paths.
     */
    private fun fileDirFromUni(uni: UniFile?): File? {
        val uri = uni?.uri ?: return null
        if ("file" != uri.scheme) return null
        val dir = File(uri.path ?: return null)
        return if (dir.isDirectory) dir else null
    }

    /**
     * Check if a directory contains at least one image file, matching against
     * the shared [GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS] whitelist so this
     * routing check recognises exactly what the reader's dir lister
     * ([com.hippo.ehviewer.gallery.DirImageFiles]) will enumerate.
     */
    @JvmStatic
    fun hasImageFiles(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { f ->
            f.isFile && StringUtils.endsWith(
                f.name.lowercase(),
                GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS
            )
        }
    }

    /** Count page-image files in [dir] using the same extension whitelist as [hasImageFiles]. */
    @JvmStatic
    fun countImageFiles(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { f ->
            f.isFile && StringUtils.endsWith(
                f.name.lowercase(),
                GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS
            )
        }
    }

    /**
     * A local copy is complete when it holds at least [expectedPages] page
     * images. An unknown server pagecount (<= 0) is treated as complete —
     * we have no basis to second-guess the directory.
     */
    @JvmStatic
    fun isLocalCopyComplete(dir: File, expectedPages: Int): Boolean {
        if (expectedPages <= 0) return true
        return countImageFiles(dir) >= expectedPages
    }
}
