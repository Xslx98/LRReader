package com.hippo.ehviewer.ui.scene.gallery.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.lrr.LRRArchivePagingSource
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.download.DownloadManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.concurrent.ExecutorService

/**
 * ViewModel for the gallery list screen. Exposes a [Flow] of [PagingData]
 * that automatically invalidates when search parameters change.
 *
 * Integration into [GalleryListScene] is deferred to the Kotlin migration
 * of that scene. This ViewModel is ready to be used once the scene is
 * converted from Java + ContentHelper to Kotlin + PagingDataAdapter.
 */
class GalleryListViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // Service accessors (read-through to ServiceRegistry so the Scene does not
    // need to import ServiceRegistry directly)
    // -------------------------------------------------------------------------

    /** The legacy EH client singleton (still used by a few code paths). */
    val ehClient: EhClient
        get() = ServiceRegistry.clientModule.ehClient

    /** The app's [DownloadManager] singleton. */
    val downloadManager: DownloadManager
        get() = ServiceRegistry.dataModule.downloadManager

    /** The global favourite status router. */
    val favouriteStatusRouter: FavouriteStatusRouter
        get() = ServiceRegistry.dataModule.favouriteStatusRouter

    /** Shared background executor for lightweight off-main-thread work. */
    val executorService: ExecutorService
        get() = ServiceRegistry.appModule.executorService

    /**
     * Encapsulates all search parameters needed to create a [LRRArchivePagingSource].
     */
    data class SearchParams(
        val filter: String? = null,
        val category: String? = null,
        val sortby: String? = "date_added",
        val order: String? = "desc",
        val newonly: Boolean = false,
        val untaggedonly: Boolean = false
    )

    private val searchParams = MutableStateFlow(SearchParams())

    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryFlow: Flow<PagingData<GalleryInfo>> = searchParams.flatMapLatest { params ->
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = PREFETCH_DISTANCE
            )
        ) {
            LRRArchivePagingSource(
                client = LRRClientProvider.getClient(),
                baseUrl = LRRClientProvider.getBaseUrl(),
                filter = params.filter,
                category = params.category,
                sortby = params.sortby,
                order = params.order,
                newonly = params.newonly,
                untaggedonly = params.untaggedonly
            )
        }.flow
    }.cachedIn(viewModelScope)

    /**
     * Trigger a new search. The existing PagingData is automatically invalidated
     * and a fresh load begins from page 0.
     */
    fun search(params: SearchParams) {
        searchParams.value = params
    }

    companion object {
        /** Matches the LANraragi default page size and GalleryListScene.LRR_PAGE_SIZE. */
        const val PAGE_SIZE = 100

        /** Number of items before the end to start loading the next page. */
        const val PREFETCH_DISTANCE = 20
    }
}
