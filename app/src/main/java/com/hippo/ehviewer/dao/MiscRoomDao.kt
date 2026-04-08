package com.hippo.ehviewer.dao

import androidx.room.*

/**
 * Room DAO for misc tables: Gallery_Tags, BOOKMARKS, SERVER_PROFILES.
 */
@Dao
interface MiscRoomDao {

    // --- Gallery_Tags ---

    @Query("SELECT * FROM Gallery_Tags ORDER BY GID ASC")
    suspend fun getAllGalleryTags(): List<GalleryTags>

    @Query("SELECT COUNT(*) FROM Gallery_Tags WHERE GID = :gid")
    suspend fun countGalleryTagsByGid(gid: Long): Int

    @Query("SELECT * FROM Gallery_Tags WHERE GID = :gid LIMIT 1")
    suspend fun queryGalleryTags(gid: Long): GalleryTags?

    @Insert
    suspend fun insertGalleryTags(galleryTags: GalleryTags)

    @Update
    suspend fun updateGalleryTags(galleryTags: GalleryTags)

    @Delete
    suspend fun deleteGalleryTags(galleryTags: GalleryTags)

    // --- BOOKMARKS ---

    @Query("SELECT * FROM BOOKMARKS ORDER BY TIME DESC")
    suspend fun getAllBookmarks(): List<BookmarkInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkInfo)

    @Query("SELECT * FROM BOOKMARKS WHERE GID = :gid")
    suspend fun loadBookmark(gid: Long): BookmarkInfo?

    @Query("DELETE FROM BOOKMARKS WHERE GID = :gid")
    suspend fun deleteBookmarkByKey(gid: Long)

    // --- SERVER_PROFILES ---

    @Query("SELECT * FROM SERVER_PROFILES ORDER BY NAME ASC")
    suspend fun getAllServerProfiles(): List<ServerProfile>

    @Query("SELECT * FROM SERVER_PROFILES WHERE IS_ACTIVE = 1 LIMIT 1")
    suspend fun getActiveProfile(): ServerProfile?

    @Query("SELECT * FROM SERVER_PROFILES WHERE URL = :url LIMIT 1")
    suspend fun findProfileByUrl(url: String): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerProfile(profile: ServerProfile): Long

    @Update
    suspend fun updateServerProfile(profile: ServerProfile)

    @Delete
    suspend fun deleteServerProfile(profile: ServerProfile)

    @Query("UPDATE SERVER_PROFILES SET IS_ACTIVE = 0")
    suspend fun deactivateAllProfiles()
}
