package com.hippo.ehviewer.client.lrr

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.hippo.ehviewer.client.data.GalleryInfo
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
 */
class LRRArchivePagingSource(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val filter: String?,
    private val category: String?,
    private val sortby: String?,
    private val order: String?,
    private val newonly: Boolean = false,
    private val untaggedonly: Boolean = false
) : PagingSource<Int, GalleryInfo>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, GalleryInfo> {
        val page = params.key ?: 0
        return try {
            val start = page * params.loadSize
            val result = LRRSearchApi.searchArchives(
                client, baseUrl,
                filter = filter,
                category = category,
                start = start,
                sortby = sortby,
                order = order,
                newonly = newonly,
                untaggedonly = untaggedonly
            )
            val items = result.data.map { it.toGalleryInfo() }
            LoadResult.Page(
                data = items,
                prevKey = if (page > 0) page - 1 else null,
                nextKey = if (items.size >= params.loadSize) page + 1 else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, GalleryInfo>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}
