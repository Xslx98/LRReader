package com.hippo.ehviewer.mapper

import com.hippo.ehviewer.dao.ArchiveLocalState
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.Mapper

/**
 * Injectable face of the archive ↔ DTO ↔ row conversions defined as
 * extension functions in [EntityMapper] and the LRR API DTO. Each
 * adapter delegates to its extension counterpart so call-site
 * ergonomics stay (kotlin extensions read better than `mapper.map(x)`);
 * the [Mapper] wrappers exist so tests and DI can substitute behavior
 * without resurrecting full models.
 *
 * Post-L1 the DownloadInfo / HistoryInfo / LocalFavoriteInfo bimappers
 * that used to live here are gone — those types are pure in-memory
 * views, not Room entities, and the persistence path now goes through
 * `ArchiveLocalState`. The single forward [archiveLocalStateToArchive]
 * mapper is the wire equivalent: decode the `archive_json` payload
 * and surface the [Archive] inside.
 */
object ArchiveMappers {

    val lrrArchiveToArchive: Mapper<LRRArchive, Archive> = Mapper { it.toArchive() }
    val lrrArchiveToArchiveDetail: Mapper<LRRArchive, ArchiveDetail> = Mapper { it.toArchiveDetail() }

    /**
     * Decode an [ArchiveLocalState] row into the [Archive] payload it
     * carries. The reverse direction (Archive → ArchiveLocalState) is
     * intentionally not exposed as a Mapper because it cannot be done
     * losslessly without choosing a subsystem
     * (download / history / favorite); callers that need to write
     * should go through the per-subsystem repository methods.
     */
    val archiveLocalStateToArchive: Mapper<ArchiveLocalState, Archive> = Mapper { it.toArchive() }
}
