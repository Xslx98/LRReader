package com.hippo.ehviewer.module

import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.lanraragi.reader.domain.ArchiveDetail
import com.hippo.ehviewer.dao.DownloadDbRepository
import com.hippo.ehviewer.dao.FavoritesRepository
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.QuickSearchRepository
import com.hippo.ehviewer.dao.SearchHistoryRepository
import com.hippo.ehviewer.download.DownloadManager
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

    /** History domain repository backed by [com.hippo.ehviewer.dao.BrowsingRoomDao]. */
    val historyRepository: HistoryRepository

    /** Server profile domain repository backed by [com.hippo.ehviewer.dao.MiscRoomDao]. */
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

    /** Download domain repository backed by [com.hippo.ehviewer.dao.DownloadRoomDao]. */
    val downloadDbRepository: DownloadDbRepository

    /** LRU cache for [ArchiveDetail] objects keyed by arcid. */
    val archiveDetailCache: LruCache<String, ArchiveDetail>

    /** Small disk cache holding per-gallery spider state for preloading. */
    val spiderInfoCache: SimpleDiskCache

    /** Evicts every entry from [archiveDetailCache]. */
    fun clearArchiveDetailCache()
}
