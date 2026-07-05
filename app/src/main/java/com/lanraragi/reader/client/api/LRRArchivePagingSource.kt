package com.lanraragi.reader.client.api

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.hippo.ehviewer.settings.AppearanceSettings
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Paging 3 source that loads gallery archives from the LANraragi search API.
 *
 * Each page corresponds to one call to GET /api/search with an offset
 * calculated as `page * pageSize`. The page key is a 0-based page index.
 *
 * @param client       OkHttpClient configured with auth interceptor
 * @param baseUrl      LANraragi server base URL
 * @param filter       search keyword/filter text (null = no filter)
 * @param category     LRR category ID (null = no category filter)
 * @param sortby       sort field, e.g. "date_added", "title" (null = server default)
 * @param order        sort order, e.g. "asc", "desc" (null = server default)
 * @param newonly      if true, only return archives flagged as new
 * @param untaggedonly if true, only return untagged archives
 * @param includeTanksProvider whether to fold Tankoubons into the list
 *   (groupby_tanks request param + TANK_ pseudo-entries in the page).
 *   Evaluated once per load so a settings toggle applies on the next refresh.
 *   Injectable because the production default reads SharedPreferences-backed
 *   settings, which plain JVM unit tests cannot touch. Default: fold per user
 *   setting, unless this server is known pre-0.9.8 (TankoubonSupportGate
 *   flips after the first failed tank open).
 */
class LRRArchivePagingSource(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val filter: String?,
    private val category: String?,
    private val sortby: String?,
    private val order: String?,
    private val newonly: Boolean = false,
    private val untaggedonly: Boolean = false,
    private val includeTanksProvider: () -> Boolean = {
        AppearanceSettings.getGroupTanks() && !TankoubonSupportGate.isUnsupported(baseUrl)
    }
) : PagingSource<Int, Archive>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val page = params.key ?: 0
        return try {
            val start = page * params.loadSize
            val includeTanks = includeTanksProvider()
            val result = LRRSearchApi.searchArchives(
                client, baseUrl,
                filter = filter,
                category = category,
                start = start,
                sortby = sortby,
                order = order,
                newonly = newonly,
                untaggedonly = untaggedonly,
                groupbyTanks = includeTanks
            )
            // Tank entries become display-only pseudo-Archives when folding is on,
            // and are dropped otherwise. nextKey keys off the raw (pre-mapping)
            // count so a dropped entry doesn't prematurely end paging.
            val items = result.toArchiveList(
                includeTanks = includeTanks,
                tankProfileId = LRRAuthManager.getActiveProfileId(),
                tankBaseUrl = baseUrl,
            )
            LoadResult.Page(
                data = items,
                prevKey = if (page > 0) page - 1 else null,
                nextKey = if (result.data.size >= params.loadSize) page + 1 else null
            )
        } catch (e: CancellationException) {
            // A superseded search (flatMapLatest) or a torn-down Scene cancels
            // the load. Let Paging observe the cancellation instead of turning
            // it into a spurious LoadResult.Error that flashes a retry/error UI.
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Archive>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}
