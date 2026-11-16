package com.lanraragi.reader

import android.content.Context
import androidx.collection.LruCache
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.FavoritesRepository
import com.lanraragi.reader.dao.FavouriteStatusRouter
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.QuickSearchRepository
import com.lanraragi.reader.dao.SearchHistoryRepository
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.module.IAppModule
import com.lanraragi.reader.module.IDataModule
import com.lanraragi.reader.module.INetworkModule
import com.lanraragi.reader.module.NetworkMonitor
import okhttp3.Cache
import com.lanraragi.reader.server.ProfileLookupCache
import okhttp3.OkHttpClient
import java.io.File
import com.lanraragi.reader.module.AppProxySelector
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.server.ServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [INetworkModule] for [ServiceRegistry.initializeForTest] whose three
 * clients are [client]. With a null [client] (or [cacheDir]) the matching
 * members throw, for tests that must not touch the network at all.
 *
 * [NetworkMonitor] always throws: retryOnFailure reads it through
 * runCatching{}.getOrDefault(false), so a throw is treated as "online",
 * which is what tests want.
 */
fun stubNetworkModule(client: OkHttpClient? = null, cacheDir: File? = null): INetworkModule =
    object : INetworkModule {
        override val cache: Cache get() = Cache(cacheDir ?: unsupported(), 1024)
        override val proxySelector: AppProxySelector get() = unsupported()
        override val okHttpClient: OkHttpClient get() = client ?: unsupported()
        override val longReadClient: OkHttpClient get() = client ?: unsupported()
        override val uploadClient: OkHttpClient get() = client ?: unsupported()
        override val networkMonitor: NetworkMonitor get() = unsupported()
    }

/**
 * [IDataModule] for tests that need no persistence: every member throws
 * UnsupportedOperationException, an Exception, so production code that
 * catches Exception (background syncs, best-effort writes) treats it as a
 * failure instead of dying on an Error. Install it rather than leaving the
 * data module out: initializeForTest keeps whatever an earlier test class
 * installed in the same Robolectric sandbox.
 */
fun stubDataModule(): IDataModule = object : IDataModule {
    override val favouriteStatusRouter: FavouriteStatusRouter get() = unsupported()
    override val downloadManager: DownloadManager get() = unsupported()
    override val historyRepository: HistoryRepository get() = unsupported()
    override val profileRepository: ProfileRepository get() = unsupported()
    override val profileLookupCache: ProfileLookupCache get() = unsupported()
    override val quickSearchRepository: QuickSearchRepository get() = unsupported()
    override val searchHistoryRepository: SearchHistoryRepository get() = unsupported()
    override val favoritesRepository: FavoritesRepository get() = unsupported()
    override val downloadDbRepository: DownloadDbRepository get() = unsupported()
    override val archiveDetailCache: LruCache<String, ArchiveDetail> get() = unsupported()
    override fun clearArchiveDetailCache() {}
}

/** [IAppModule] that only supplies [context]; nothing else. */
fun stubAppModule(context: Context): IAppModule = object : IAppModule {
    override fun getContext(): Context = context
}

private fun unsupported(): Nothing = throw UnsupportedOperationException()

/**
 * Makes [profiles] the server profiles of the process (what [ServerSession]
 * and the network interceptors see), keeping the rest of the installed data
 * module. The cache collects on an unconfined scope, so it is loaded on
 * return and every later write to the returned flow is visible at once.
 * Call it after the test's own [ServiceRegistry.initializeForTest].
 */
fun installProfiles(vararg profiles: ServerProfile): MutableStateFlow<List<ServerProfile>> {
    val flow = MutableStateFlow(profiles.toList())
    val cache = ProfileLookupCache(flow, CoroutineScope(Dispatchers.Unconfined))
    val current = runCatching { ServiceRegistry.dataModule }.getOrNull() ?: stubDataModule()
    ServiceRegistry.initializeForTest(
        coroutine = null,
        data = object : IDataModule by current {
            override val profileLookupCache: ProfileLookupCache = cache
        },
    )
    return flow
}

/**
 * [installProfiles] with one active profile at [url], storing [apiKey] for it
 * (the secure store must be initialised for a non-null key).
 */
fun installActiveServer(
    url: String,
    apiKey: String? = null,
    id: Long = 1L,
    allowCleartext: Boolean = true,
): MutableStateFlow<List<ServerProfile>> {
    val flow = installProfiles(ServerProfile(id, "Test server", url, true, allowCleartext))
    if (apiKey != null) ServerSession.setApiKeyForProfile(id, apiKey)
    return flow
}
