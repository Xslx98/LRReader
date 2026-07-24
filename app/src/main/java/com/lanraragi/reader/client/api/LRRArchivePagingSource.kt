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
 * Each page corresponds to one call to GET /api/search. The page key is a
 * **raw item offset** into the server-side result set, not a dense page
 * index: /api/search has no page-size parameter — the server returns
 * `archives_per_page` rows per request regardless of the client's
 * `params.loadSize` — so the only safe advance is by the count actually
 * returned, and the only safe termination signal is `recordsFiltered`
 * (audit NET-2; index-based math silently truncated the list when the
 * server page was smaller than 50 and duplicated rows when larger).
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
    },
    private val hideCompletedProvider: () -> Boolean = {
        AppearanceSettings.getHideCompleted()
    }
) : PagingSource<Int, Archive>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val start = params.key ?: 0
        return try {
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
                groupbyTanks = includeTanks,
                hideCompleted = hideCompletedProvider()
            )
            // Tank entries become display-only pseudo-Archives when folding is on,
            // and are dropped otherwise. nextKey advances by the raw (pre-mapping)
            // count so a dropped entry still consumes its server-side offset slot.
            val items = result.toArchiveList(
                includeTanks = includeTanks,
                tankProfileId = LRRAuthManager.getActiveProfileId(),
                tankBaseUrl = baseUrl,
            )
            val rawCount = result.data.size
            LoadResult.Page(
                data = items,
                prevKey = if (start > 0) (start - params.loadSize).coerceAtLeast(0) else null,
                // rawCount > 0 guards against a malformed empty page that still
                // claims more records — advancing by zero would re-request the
                // same offset forever.
                nextKey = if (rawCount > 0 && start + rawCount < result.recordsFiltered) {
                    start + rawCount
                } else {
                    null
                }
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
        // With offset keys and placeholders disabled, every loaded list is
        // contiguous from offset 0, so the anchor position is itself the
        // offset to refresh from.
        return state.anchorPosition
    }
}
