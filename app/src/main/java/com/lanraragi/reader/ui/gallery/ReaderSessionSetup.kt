package com.lanraragi.reader.ui.gallery

import android.content.Context
import android.util.Log
import com.lanraragi.reader.storage.StorageFile
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.DirGalleryProvider
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.LRRGalleryProvider
import com.lanraragi.reader.gallery.ReadingSessionTracker
import com.lanraragi.reader.gallery.TankGalleryProvider
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.ui.GalleryActivity
import java.io.File
import kotlinx.coroutines.launch

/** What a reader session is built from: the provider for its source, and its history entry. */
object ReaderSessionSetup {

    /** The page provider for a reader launch, or null when the launch is missing its source. */
    @Suppress("LongParameterList")
    fun createProvider(
        context: Context,
        action: String?,
        filename: String?,
        downloadDir: String?,
        archive: Archive?,
        tankSeed: TankSessionSeed?,
    ): GalleryProvider2? = when (action) {
        GalleryActivity.ACTION_DIR -> {
            filename?.let { StorageFile(File(it)) }?.let { dir ->
                if (archive != null) {
                    DirGalleryProvider(
                        dir, context, archive.arcid, archive.serverProfileId,
                        archive.progress, archive.lastreadtime,
                    ).also {
                        it.expectedPageCount = archive.pagecount
                    }
                } else {
                    DirGalleryProvider(dir)
                }
            }
        }
        GalleryActivity.ACTION_LRR -> archive?.let {
            LRRGalleryProvider(context, it.arcid, it.serverProfileId, downloadDir?.let(::File))
        }
        GalleryActivity.ACTION_TANK -> tankSeed?.let { TankGalleryProvider(context, it) }
        else -> null
    }

    /**
     * Reading-session seam: consumed by the continue-reading shortcut and
     * the daily reading aggregate. DIR-mode reading (no Archive) stays
     * untracked — without start(), stop() in onStop is a no-op. A tank
     * session tracks as ONE logical archive keyed by the tank id (global
     * pages; completion = crossing the tank's end) — tank-only
     * bookkeeping, and the shortcut/widget deep link resumes the
     * composite session via MainActivity's isTankoubonId branch.
     */
    fun startReadingSession(
        tracker: ReadingSessionTracker,
        archive: Archive?,
        tankSeed: TankSessionSeed?,
        startPage: Int,
        providerSize: () -> Int,
    ) {
        archive?.let {
            tracker.start(it.arcid, it.serverProfileId, startPage, it.pagecount)
        } ?: tankSeed?.let { seed ->
            tracker.start(seed.tankId, seed.profileId, startPage, providerSize().coerceAtLeast(0))
        }
    }

    /**
     * Reader entry counts as reading: ensure history-subsystem membership for
     * the archive so per-archive state (intra-page scroll fraction, etc.)
     * lands on a row that the history queries can see. This is the only place
     * a single archive enters history: opening its detail page or starting
     * its download is not reading (audit 2026-10-04). Fire-and-forget on the
     * app-wide IO scope.
     *
     * Tank sessions get ONE history row for the whole tank (tank-only
     * bookkeeping): a pseudo-archive keyed by the TANK_ id whose thumbnail
     * rides the tank cover route. HistoryScene branches its click on
     * isTankoubonId and resumes the composite session.
     */
    fun recordHistory(archive: Archive?, tankSeed: TankSessionSeed?) {
        archive?.let {
            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    ServiceRegistry.dataModule.historyRepository.putHistoryInfo(it)
                } catch (e: Exception) {
                    Log.w("GalleryActivity", "Failed to record history: ${e.message}")
                }
            }
        }
        tankSeed?.let { seed ->
            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    val url = resolveSourceBaseUrl(
                        seed.profileId, ServiceRegistry.dataModule.profileLookupCache
                    )
                    val pseudo = Archive(
                        arcid = seed.tankId,
                        title = seed.tankName,
                        tags = emptyMap(),
                        pagecount = seed.members.sumOf { it.pagecount.coerceAtLeast(0) },
                        progress = 0,
                        extension = "",
                        filename = "",
                        thumbnailUrl = LRRTankoubonApi.getTankoubonThumbnailUrl(url, seed.tankId),
                        rating = 0f,
                        isnew = false,
                        lastreadtime = 0L,
                        summary = null,
                        serverProfileId = seed.profileId,
                    )
                    ServiceRegistry.dataModule.historyRepository.putHistoryInfo(pseudo)
                } catch (e: Exception) {
                    Log.w("GalleryActivity", "Failed to record tank history: ${e.message}")
                }
            }
        }
    }
}
