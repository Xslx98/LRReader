package com.lanraragi.reader.ui.scene.gallery.list

import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.archive.ArchiveSearchSource
import com.lanraragi.reader.archive.ListUrlBuilder
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.tankoubon.TankBrowse
import com.lanraragi.reader.tankoubon.TankRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one list load asks for: the scene's query at the time of the request. */
data class GalleryListQuery(
    /** The `/api/search` inputs (archive mode). */
    val search: GalleryListLoader.SearchInputs,
    val sortBy: String,
    val order: String,
    /** The list shows tanks only (docs/plan-2.4.0-tank-ux.md, PR-B): the tank list replaces `/api/search`. */
    val tankMode: Boolean = false,
    /** Tank mode's local filter: the search bar's keyword as typed. */
    val tankKeyword: String = "",
    val tankSort: TankBrowse.Sort = TankBrowse.Sort.NAME,
) {
    companion object {
        fun of(
            urlBuilder: ListUrlBuilder?,
            sortBy: String,
            order: String,
            tankMode: Boolean,
            tankSort: TankBrowse.Sort,
        ) = GalleryListQuery(
            search = GalleryListLoader.searchInputsFor(urlBuilder),
            sortBy = sortBy,
            order = order,
            tankMode = tankMode,
            tankKeyword = urlBuilder?.keyword.orEmpty(),
            tankSort = tankSort,
        )
    }
}

/** One outcome of a list load, handed to the list on the main thread. */
sealed interface GalleryListLoad {

    /**
     * The rows of one page. [pageCount] and [nextPage] are ContentLayout's
     * dense page bookkeeping; [issued] is the `/api/search` query behind the
     * rows (null for tank mode, which never anchors a reading context).
     */
    data class Page(
        val rows: List<Archive>,
        val pageCount: Int,
        val nextPage: Int,
        @StringRes val emptyText: Int,
        val issued: GalleryListViewModel.SearchParams?,
    ) : GalleryListLoad

    /** tankId → stand-in cover URL for tanks whose generated cover is missing: patch those rows. */
    data class TankCovers(val thumbnailUrls: Map<String, String>) : GalleryListLoad

    data class Failed(val error: Exception) : GalleryListLoad

    /** A failure the list explains with its own text. */
    data class FailedWith(@StringRes val message: Int) : GalleryListLoad
}

/**
 * The list's data path, owned by [GalleryListViewModel] and run on its scope:
 * one load at a time (a new one cancels the one it supersedes — audit
 * 2026-10-13 PERF-12), the dense-page → raw-offset chain (audit NET-2), and
 * tank mode's whole-list load with its missing-cover probe. Main-thread only.
 */
class GalleryListLoader(
    private val scope: CoroutineScope,
    private val tanks: () -> TankRepository,
    private val search: ArchiveSearchSource = ArchiveSearchSource(),
) {

    /** The two `/api/search` inputs a [ListUrlBuilder] contributes. */
    data class SearchInputs(val filter: String?, val categoryId: String?)

    /**
     * Dense-page-index → raw-offset mapping. The server decides how many rows
     * a request returns (archives_per_page), so the next page's start offset
     * must be chained from the previous load instead of computed as
     * page * PAGE_SIZE (audit NET-2).
     */
    private val pageOffsets = PageOffsetTracker(PAGE_SIZE)

    /**
     * tankId → stand-in cover for tanks whose generated cover is missing,
     * from the last probe. Applied to later loads at once.
     */
    private var tankCoverFallbacks: Map<String, TankRepository.CoverFallback> = emptyMap()

    /** The in-flight load. */
    @VisibleForTesting
    internal var current: Job? = null
        private set

    /**
     * Loads dense [page] of [query], cancelling the load it supersedes (the
     * list drops a superseded task's result anyway). [deliver] runs on the
     * main thread — synchronously when no server is configured — and returns
     * whether the list still wants the result (its task is current); only
     * then is the page's next offset chained.
     */
    fun load(query: GalleryListQuery, page: Int, deliver: (GalleryListLoad) -> Boolean) {
        current?.cancel()
        current = null
        // Signal failure instead of returning silently, otherwise the refresh
        // spinner spins forever when no server profile is configured.
        val conn = ServerConnections.active()?.takeIf { it.baseUrl.isNotEmpty() }
        if (conn == null) {
            deliver(GalleryListLoad.FailedWith(R.string.lrr_no_servers))
            return
        }
        current = scope.launch {
            if (query.tankMode) loadTanks(conn, query, deliver) else loadArchives(conn, query, page, deliver)
        }
    }

    private suspend fun loadArchives(
        conn: ServerConnection,
        query: GalleryListQuery,
        page: Int,
        deliver: (GalleryListLoad) -> Boolean,
    ) {
        val issued = GalleryListViewModel.SearchParams(
            filter = query.search.filter,
            category = query.search.categoryId,
            sortby = query.sortBy,
            order = query.order,
        )
        // Every refresh restarts at page 0; a stale chain from the previous
        // result set must not leak into the new one.
        if (page == 0) pageOffsets.reset()
        val startOffset = pageOffsets.offsetFor(page)
        val result = try {
            withContext(Dispatchers.IO) {
                search.load(
                    conn,
                    ArchiveSearchSource.Query(
                        filter = issued.filter,
                        category = issued.category,
                        sortby = issued.sortby,
                        order = issued.order,
                    ),
                    // ContentLayout counts dense page indexes (0,1,2,…) but the
                    // server is addressed by raw item offsets (NET-2).
                    offset = startOffset,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LRR search failed", e)
            deliver(GalleryListLoad.Failed(e))
            return
        }
        val hasMore = result.nextOffset != null
        val accepted = deliver(
            GalleryListLoad.Page(
                rows = result.items,
                pageCount = if (hasMore) page + 2 else page + 1,
                nextPage = if (hasMore) page + 1 else 0,
                emptyText = R.string.gallery_list_empty_hit,
                issued = issued,
            )
        )
        if (accepted) pageOffsets.recordLoaded(page, result.nextOffset)
    }

    /**
     * Tanks-only load: the whole tank list in one page (the server can neither
     * search nor sort tanks), filtered and ordered locally by the search bar's
     * keyword and the chosen sort. The missing-cover probe runs after the rows
     * land and patches the affected rows in place.
     */
    private suspend fun loadTanks(
        conn: ServerConnection,
        query: GalleryListQuery,
        deliver: (GalleryListLoad) -> Boolean,
    ) {
        try {
            val repository = tanks()
            val all = repository.loadAll(conn)
            val fallbacks = tankCoverFallbacks
            val rows = withContext(Dispatchers.IO) {
                val lastRead = if (query.tankSort == TankBrowse.Sort.RECENT_READ) tankReadTimes() else emptyMap()
                TankBrowse.order(all, query.tankKeyword, query.tankSort, lastRead).map {
                    TankBrowse.toArchive(
                        it, conn.profileId, conn.baseUrl, fallbacks[it.id]?.thumbnailUrl, lastRead[it.id] ?: 0L,
                    )
                }
            }
            deliver(GalleryListLoad.Page(rows, pageCount = 1, nextPage = 0, R.string.tankoubons_empty, issued = null))
            val probed = repository.probeCoverFallbacks(conn, all)
            tankCoverFallbacks = probed
            if (probed.isNotEmpty()) deliver(GalleryListLoad.TankCovers(probed.mapValues { it.value.thumbnailUrl }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deliver(
                if (TankoubonSupportGate.markFrom(conn.baseUrl, e)) {
                    GalleryListLoad.FailedWith(R.string.tankoubons_unsupported)
                } else {
                    GalleryListLoad.Failed(e)
                }
            )
        }
    }

    /** tankId → the local `TANK_` history row's time, for the recently-read sort. */
    private suspend fun tankReadTimes(): Map<String, Long> =
        ServiceRegistry.dataModule.historyRepository.getHistoryLazyList()
            .filter { isTankoubonId(it.arcid) }
            .associate { it.arcid to it.time }

    companion object {
        private const val TAG = "GalleryListLoader"

        /** Nominal page size: the raw-offset fallback for a page never chained. */
        const val PAGE_SIZE = 50

        /**
         * Builder → search parameters. The keyword is plain `filter` text and
         * [ListUrlBuilder.categoryId] is the `category` parameter; both may be
         * present (search within a category). The retired "category:<id>"
         * keyword protocol is deliberately NOT recognised here — MIGRATION_30_31
         * rewrote every persisted row, so such a keyword is ordinary text.
         */
        @JvmStatic
        fun searchInputsFor(urlBuilder: ListUrlBuilder?): SearchInputs = SearchInputs(
            filter = urlBuilder?.keyword?.trim()?.takeIf { it.isNotEmpty() },
            categoryId = urlBuilder?.categoryId?.takeIf { it.isNotBlank() }
        )
    }
}
