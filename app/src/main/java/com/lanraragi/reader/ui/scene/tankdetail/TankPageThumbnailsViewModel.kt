package com.lanraragi.reader.ui.scene.tankdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.PageThumbnailCache
import com.lanraragi.reader.client.api.PageThumbnailRepository
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.TankPageMap
import com.lanraragi.reader.tankoubon.TankPageGridLayout
import com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailsViewModel.PageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page thumbnails for the tank detail page's continuous GLOBAL-page grid
 * (spec 2026-09-22 §4.7): the tank page source behind the shared
 * [PageThumbnailRepository] / [PageThumbnailCache]. Page counts come from
 * member metadata (no `/files` round trip — the composite reader corrects
 * them lazily); every global page resolves to (member arcid, local page)
 * through a [TankPageMap] and is fetched/cached under that member's key,
 * so a member's tiles are shared with its own detail page.
 *
 * Same threading contract as [com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailsViewModel.requestPage]:
 * never call [requestPage] from RecyclerView layout / bind / scroll
 * callbacks without posting first.
 */
class TankPageThumbnailsViewModel : ViewModel() {

    /** Grid layout of the current members; null before [start]. */
    private val _layout = MutableStateFlow<TankPageGridLayout.Layout?>(null)
    val layout: StateFlow<TankPageGridLayout.Layout?> = _layout.asStateFlow()

    /** Per-GLOBAL-page request state; a missing key = idle or cached. */
    private val _pageStates = MutableStateFlow<Map<Int, PageState>>(emptyMap())
    val pageStates: StateFlow<Map<Int, PageState>> = _pageStates.asStateFlow()

    private var members: List<Archive> = emptyList()
    private var pageMap: TankPageMap? = null
    private var baseUrl: String? = null

    /** Identity of the current source so a re-bind with the same members is a no-op. */
    private var sourceKey: String? = null

    /** Publishes the grid for [newMembers] (server order) served from [newBaseUrl]. */
    fun start(newMembers: List<Archive>, newBaseUrl: String) {
        val key = newBaseUrl + "|" + newMembers.joinToString(",") { it.arcid + ":" + it.pagecount }
        if (key == sourceKey) return
        sourceKey = key
        members = newMembers
        baseUrl = newBaseUrl
        pageMap = TankPageMap(newMembers.map { it.pagecount })
        _pageStates.value = emptyMap()
        _layout.value = TankPageGridLayout.build(newMembers.map { it.pagecount })
    }

    /** (member, local page0) of global [global0]; null when out of range. */
    fun locate(global0: Int): Pair<Archive, Int>? {
        val (memberIndex, page0) = pageMap?.locate(global0) ?: return null
        val member = members.getOrNull(memberIndex) ?: return null
        return member to page0
    }

    /** Idempotent thumbnail request for global page [global0]; see the class KDoc for threading. */
    fun requestPage(global0: Int) {
        val url = baseUrl ?: return
        val (member, page0) = locate(global0) ?: return
        val cur = _pageStates.value[global0]
        if (cur is PageState.Loading || cur is PageState.Failed) return
        if (PageThumbnailCache.get(member.arcid, page0) != null) return

        _pageStates.update { it + (global0 to PageState.Loading) }
        val key = sourceKey
        viewModelScope.launch(Dispatchers.IO + ServiceRegistry.coroutineModule.exceptionHandler) {
            val result = PageThumbnailRepository.loadThumbnail(member.arcid, page0, url)
            if (sourceKey != key) return@launch
            _pageStates.update { current ->
                if (result.isSuccess) current - global0 else current + (global0 to PageState.Failed)
            }
        }
    }

    /** Clears a [PageState.Failed] page and re-requests it. */
    fun retryPage(global0: Int) {
        if (_pageStates.value[global0] !is PageState.Failed) return
        _pageStates.update { it - global0 }
        requestPage(global0)
    }
}
