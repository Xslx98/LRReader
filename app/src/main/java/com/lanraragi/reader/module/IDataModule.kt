package com.lanraragi.reader.module

import androidx.collection.LruCache
import com.lanraragi.framework.beerbelly.SimpleDiskCache
import com.lanraragi.reader.FavouriteStatusRouter
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.FavoritesRepository
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.QuickSearchRepository
import com.lanraragi.reader.dao.SearchHistoryRepository
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.client.api.ProfileLookupCache

/**
 * Abstraction over [DataModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * in-memory stubs and fake download managers.
 */
interface IDataModule {

    /** Fan-out channel notifying listeners of favourite-slot changes. */
    val favouriteStatusRouter: FavouriteStatusRouter

    /** Global download state manager and persistence gateway. */
    val downloadManager: DownloadManager

    /** History domain repository backed by [com.lanraragi.reader.dao.BrowsingRoomDao]. */
    val historyRepository: HistoryRepository

    /** Server profile domain repository backed by [com.lanraragi.reader.dao.MiscRoomDao]. */
    val profileRepository: ProfileRepository

    /**
     * Synchronous in-memory snapshot of [ProfileRepository.observeAll], used by
     * OkHttp interceptors (no suspending) and the download worker (sync
     * resolve at construction) to look up profiles by id / request URL.
     */
    val profileLookupCache: ProfileLookupCache

    /** Quick search domain repository backed by BrowsingRoomDao. */
    val quickSearchRepository: QuickSearchRepository

    /** Per-profile automatic search history repository backed by BrowsingRoomDao. */
    val searchHistoryRepository: SearchHistoryRepository

    /** Local favorites domain repository backed by BrowsingRoomDao. */
    val favoritesRepository: FavoritesRepository

    /** Download domain repository backed by [com.lanraragi.reader.dao.DownloadRoomDao]. */
    val downloadDbRepository: DownloadDbRepository

    /** LRU cache for [ArchiveDetail] objects keyed by arcid. */
    val archiveDetailCache: LruCache<String, ArchiveDetail>

    /** Small disk cache holding per-gallery spider state for preloading. */
    val spiderInfoCache: SimpleDiskCache

    /** Evicts every entry from [archiveDetailCache]. */
    fun clearArchiveDetailCache()
}
