/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.scene.gallery.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LrrFileListCache
import com.lanraragi.reader.client.api.OrphanProfileException
import com.lanraragi.reader.client.api.PageThumbnailCache
import com.lanraragi.reader.client.api.PageThumbnailRepository
import com.lanraragi.reader.client.api.probeSourceHealthy
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * ViewModel for the per-page thumbnail grid shown at the bottom of
 * [GalleryDetailScene]. Owns the request lifecycle independently of
 * [GalleryDetailViewModel] — the detail-page metadata flow and the
 * thumbnail-list flow fail independently and recover independently.
 *
 * **State shape:**
 *  - [state] holds the page-count + error tier (one shot per archive
 *    enter), driven by the `/files` fetch.
 *  - [pageStates] tracks per-page request lifecycle (Loading / Failed
 *    only). A "Ready" page is denoted by *absence* from the map *plus*
 *    a bitmap present in [PageThumbnailCache]. Keeping the cache as
 *    the sole bitmap holder lets the LRU eviction actually free
 *    memory — if the map carried `Ready(bitmap)` entries the LRU
 *    eviction would be a no-op until the entire detail page exited.
 *
 * **Activity-scoped:** the Scene obtains this VM via
 * `ViewModelProvider(requireActivity())`, mirroring
 * [GalleryDetailViewModel]. [resetForNewEntry] must be called whenever
 * the Scene's archive arg changes, otherwise stale page-states from a
 * previous archive shadow the new grid.
 */
class PageThumbnailsViewModel : ViewModel() {

    /** Top-level state of the page-thumbnail list. */
    sealed class State {
        /** Initial state before [start] runs. */
        object Idle : State()

        /** Page list is being fetched from the server. */
        object Loading : State()

        /** Page list has been retrieved; UI renders [pageCount] empty slots. */
        data class Loaded(val pageCount: Int) : State()

        /**
         * Page list fetch failed. [tier] discriminates the user-facing
         * message (unreachable server vs deleted archive vs generic).
         */
        data class Error(val tier: ErrorTier) : State()
    }

    /**
     * Differentiates the three user-visible flavours of a failed
     * `/files` fetch. Drives the localized empty-state text in the
     * Scene.
     */
    enum class ErrorTier { UNREACHABLE, DELETED, GENERIC }

    /**
     * Per-page request state. **No Bitmap field on purpose** — see the
     * class kdoc on why the cache is the sole bitmap holder.
     */
    sealed class PageState {
        /** Request is in-flight. */
        object Loading : PageState()

        /** Request finished unsuccessfully (network error, 4xx, decode failure, 202 budget exhausted). */
        object Failed : PageState()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pageStates = MutableStateFlow<Map<Int, PageState>>(emptyMap())
    val pageStates: StateFlow<Map<Int, PageState>> = _pageStates.asStateFlow()

    private var currentArcid: String? = null
    private var currentBaseUrl: String? = null
    private var initJob: Job? = null

    /**
     * Begin loading the page list for [arcid]. Idempotent — calling
     * with the same arcid while [state] is [State.Loaded] is a no-op.
     *
     * **Cross-server contract:** [sourceProfileId] must be the
     * archive's *source* profile id (read from `Archive.serverProfileId`
     * which the Scene populated from the Room nav arg), **not** the
     * active profile. Without this, archives downloaded from server B
     * while active=A would route the thumbnail fetch to A and return
     * 404. Mirrors [GalleryDetailViewModel.resolveSourceServerUrl].
     */
    fun start(arcid: String, sourceProfileId: Long) {
        if (currentArcid == arcid && _state.value is State.Loaded) return
        if (currentArcid == arcid && _state.value is State.Loading) return

        resetForNewEntry()
        currentArcid = arcid

        initJob = viewModelScope.launch(
            Dispatchers.IO + ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            _state.value = State.Loading
            val client = ServiceRegistry.networkModule.okHttpClient
            try {
                val baseUrl = resolveSourceBaseUrl(
                    sourceProfileId,
                    ServiceRegistry.dataModule.profileLookupCache
                )
                currentBaseUrl = baseUrl

                // Best-effort warm path. LrrFileListCache is keyed by the
                // source server URL, so a cross-server read hits the entry
                // stored for that server (not the active profile's). We
                // always fall through to a fresh fetch on miss.
                val cached = LrrFileListCache.get(baseUrl, arcid)
                val pages = cached ?: LRRArchiveApi.getFileList(client, baseUrl, arcid).also {
                    LrrFileListCache.put(baseUrl, arcid, it)
                }

                // Race-guard: if the user navigated away (resetForNewEntry
                // wiped currentArcid) while the fetch was in flight, do
                // not stomp the cleared state.
                if (currentArcid == arcid) {
                    _state.value = State.Loaded(pages.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentArcid == arcid) {
                    _state.value = classifyError(e, currentBaseUrl, client)
                }
            }
        }
    }

    /**
     * Request a single page thumbnail. Idempotent — concurrent /
     * repeated calls for the same [page] are coalesced by the
     * Repository's in-flight registry. Returns immediately; observers
     * see the result via [pageStates] + [PageThumbnailCache].
     */
    fun requestPage(page: Int) {
        val arcid = currentArcid ?: return
        val baseUrl = currentBaseUrl ?: return
        val loaded = _state.value as? State.Loaded ?: return
        if (page < 0 || page >= loaded.pageCount) return

        // Already in-flight or already failed — do not re-issue.
        // Cache-hit (Ready) is implicit via the absence-from-map convention.
        val cur = _pageStates.value[page]
        if (cur is PageState.Loading || cur is PageState.Failed) return
        if (PageThumbnailCache.get(arcid, page) != null) return

        _pageStates.update { it + (page to PageState.Loading) }
        viewModelScope.launch(
            Dispatchers.IO + ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val result = PageThumbnailRepository.loadThumbnail(arcid, page, baseUrl)
            // Race-guard: drop the result if a navigation reset the VM.
            if (currentArcid != arcid) return@launch
            _pageStates.update { current ->
                if (result.isSuccess) {
                    current - page  // success ⇒ remove from map; cache holds the bitmap
                } else {
                    current + (page to PageState.Failed)
                }
            }
        }
    }

    /**
     * Manually retry a [PageState.Failed] page. Clears the Failed
     * status and reissues the request. Called from the Scene when the
     * user taps a failed thumbnail.
     */
    fun retryPage(page: Int) {
        if (_pageStates.value[page] !is PageState.Failed) return
        _pageStates.update { it - page }
        requestPage(page)
    }

    /**
     * Clear all per-entry state. Must be called from the Scene
     * `handleArgs` before writing a new arcid, mirroring
     * [GalleryDetailViewModel.resetForNewEntry]. The bitmap cache is
     * **not** invalidated here — keeping it warm lets a back→forward
     * navigation reuse work.
     */
    fun resetForNewEntry() {
        initJob?.cancel()
        initJob = null
        currentArcid = null
        currentBaseUrl = null
        _state.value = State.Idle
        _pageStates.value = emptyMap()
    }

    private suspend fun classifyError(
        e: Exception,
        baseUrl: String?,
        client: OkHttpClient,
    ): State.Error {
        if (e is OrphanProfileException) {
            return State.Error(ErrorTier.UNREACHABLE)
        }
        if (e is LRRHttpException && (e.code == HTTP_BAD_REQUEST || e.code == HTTP_NOT_FOUND)) {
            val healthy = baseUrl?.takeIf { it.isNotEmpty() }
                ?.let { probeSourceHealthy(client, it) }
                ?: false
            return if (healthy) State.Error(ErrorTier.DELETED)
            else State.Error(ErrorTier.UNREACHABLE)
        }
        if (e is IOException) {
            return State.Error(ErrorTier.UNREACHABLE)
        }
        return State.Error(ErrorTier.GENERIC)
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
