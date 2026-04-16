package com.hippo.ehviewer.dao

import com.hippo.ehviewer.client.data.GalleryInfo

/**
 * Repository for local-favorites-related database operations, backed by [BrowsingRoomDao].
 *
 * Thin delegation layer extracted from [com.hippo.ehviewer.EhDB] as part of the
 * incremental God Object decomposition. No business logic beyond what EhDB
 * already had (duplicate check, timestamp assignment).
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class FavoritesRepository(private val dao: BrowsingRoomDao) {

    suspend fun putLocalFavorite(galleryInfo: GalleryInfo) {
        val arcid = galleryInfo.arcid
        val exists = if (arcid != null) {
            dao.loadLocalFavoriteByArcid(arcid) != null
        } else {
            dao.loadLocalFavorite(galleryInfo.gid) != null
        }
        if (!exists) {
            val info = if (galleryInfo is LocalFavoriteInfo) {
                galleryInfo
            } else {
                LocalFavoriteInfo(galleryInfo).also { it.time = System.currentTimeMillis() }
            }
            dao.insertLocalFavorite(info)
        }
    }

    @Deprecated("Use arcid-based overload", ReplaceWith("removeLocalFavorite(arcid)"))
    suspend fun removeLocalFavorite(gid: Long) {
        dao.deleteLocalFavoriteByKey(gid)
    }

    suspend fun removeLocalFavorite(arcid: String) {
        dao.deleteLocalFavoriteByArcid(arcid)
    }

    suspend fun removeLocalFavorites(gidArray: LongArray) {
        for (gid in gidArray) {
            dao.deleteLocalFavoriteByKey(gid)
        }
    }

    @Deprecated("Use arcid-based overload", ReplaceWith("containsLocalFavorite(arcid)"))
    suspend fun containsLocalFavorite(gid: Long): Boolean {
        return dao.loadLocalFavorite(gid) != null
    }

    suspend fun containsLocalFavorite(arcid: String): Boolean {
        return dao.loadLocalFavoriteByArcid(arcid) != null
    }
}
