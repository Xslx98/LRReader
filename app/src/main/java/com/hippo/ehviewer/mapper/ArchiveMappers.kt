package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.Bimapper
import com.lanraragi.reader.domain.Mapper

/**
 * Injectable face of the archive ↔ entity ↔ DTO conversions defined as
 * extension functions in [EntityMapper] and the LRR API DTO. Each
 * adapter delegates to its extension counterpart so call-site ergonomics
 * stay (kotlin extensions read better than `mapper.map(x)`); the
 * [Mapper] wrappers exist so tests and DI can substitute behavior
 * without resurrecting full models.
 *
 * Wire L1 (three-table unification) plans to introduce a single
 * `ArchiveLocalState` entity. When that lands the existing
 * [downloadInfoToArchive] / [historyInfoToArchive] members will be
 * replaced by an `archiveLocalStateToArchive` Bimapper without
 * touching call sites.
 */
object ArchiveMappers {

    val archiveToDownloadInfo: Mapper<Archive, DownloadInfo> = Mapper { it.toDownloadInfo() }
    val downloadInfoToArchive: Mapper<DownloadInfo, Archive> = Mapper { it.toArchive() }

    val archiveToHistoryInfo: Mapper<Archive, HistoryInfo> = Mapper { it.toHistoryInfo() }
    val historyInfoToArchive: Mapper<HistoryInfo, Archive> = Mapper { it.toArchive() }

    val galleryDetailToArchive: Mapper<GalleryDetail, Archive> = Mapper { it.toArchive() }
    val galleryDetailToArchiveDetail: Mapper<GalleryDetail, ArchiveDetail> = Mapper { it.toArchiveDetail() }

    val lrrArchiveToArchive: Mapper<LRRArchive, Archive> = Mapper { it.toArchive() }
    val lrrArchiveToArchiveDetail: Mapper<LRRArchive, ArchiveDetail> = Mapper { it.toArchiveDetail() }
    val lrrArchiveToGalleryDetail: Mapper<LRRArchive, GalleryDetail> = Mapper { it.toGalleryDetail() }

    /** Round-trip wrapper for the DOWNLOADS-row ↔ Archive conversion pair. */
    val downloadInfoBimapper: Bimapper<Archive, DownloadInfo> = Bimapper(
        forward = archiveToDownloadInfo,
        backward = downloadInfoToArchive,
    )

    /** Round-trip wrapper for the HISTORY-row ↔ Archive conversion pair. */
    val historyInfoBimapper: Bimapper<Archive, HistoryInfo> = Bimapper(
        forward = archiveToHistoryInfo,
        backward = historyInfoToArchive,
    )
}
