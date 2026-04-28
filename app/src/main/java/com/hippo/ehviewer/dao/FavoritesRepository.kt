package com.hippo.ehviewer.dao

/**
 * Repository for local-favorites-related database operations, backed
 * (post-L1) by [ArchiveLocalStateDao] / the unified
 * `ARCHIVE_LOCAL_STATE` table.
 *
 * Public surface is unchanged: only the read/remove primitives that
 * GalleryDetailViewModel actually uses are exposed. The legacy
 * `putLocalFavorite` write API and gid-based overloads had no callers
 * post-W36 and stayed deleted across L1.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class FavoritesRepository(
    private val dao: ArchiveLocalStateDao,
    @Suppress("UNUSED_PARAMETER") database: AppDatabase,
) {

    suspend fun removeLocalFavorite(arcid: String) {
        dao.clearFavoriteSubsystem(arcid)
        dao.deleteIfNoSubsystem(arcid)
    }

    suspend fun containsLocalFavorite(arcid: String): Boolean {
        return dao.favoriteCount(arcid) > 0
    }
}
