package com.lanraragi.reader.ui.reader

import android.content.Context
import com.lanraragi.reader.storage.StorageFile
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.reading.DirGalleryProvider
import com.lanraragi.reader.reading.GalleryProvider2
import com.lanraragi.reader.reading.LRRGalleryProvider
import com.lanraragi.reader.reading.ReadingHistoryRecorder
import com.lanraragi.reader.reading.ReadingSessionTracker
import com.lanraragi.reader.reading.TankGalleryProvider
import com.lanraragi.reader.reading.TankSessionSeed
import com.lanraragi.reader.ui.GalleryActivity
import java.io.File

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

    /** Reader entry counts as reading: see [ReadingHistoryRecorder]. */
    fun recordHistory(archive: Archive?, tankSeed: TankSessionSeed?) {
        ReadingHistoryRecorder.default.record(archive, tankSeed)
    }
}
