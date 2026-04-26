package com.hippo.ehviewer.dao

/**
 * Repository for local-favorites-related database operations, backed by [BrowsingRoomDao].
 *
 * Thin delegation layer extracted from [com.hippo.ehviewer.EhDB] as part of the
 * incremental God Object decomposition.
 *
 * The write API (`putLocalFavorite`) and the gid-based deprecated overloads
 * had no callers anywhere in the codebase post-W36 wave; the repository now
 * exposes only the arcid-keyed read/remove primitives that GalleryDetailViewModel
 * (and future callers) actually use.
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class FavoritesRepository(private val dao: BrowsingRoomDao) {

    suspend fun removeLocalFavorite(arcid: String) {
        dao.deleteLocalFavoriteByArcid(arcid)
    }

    suspend fun containsLocalFavorite(arcid: String): Boolean {
        return dao.loadLocalFavoriteByArcid(arcid) != null
    }
}
