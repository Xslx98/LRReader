package com.lanraragi.reader.ui.scene.gallery.detail

import com.lanraragi.reader.domain.ArchiveDetail
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot outcomes of the detail request, delivered to the scene's
 * view-scoped collectors.
 *
 * The request starts in onCreateView and the collectors subscribe in
 * onViewCreated, a main-thread dispatch later. A response that landed in
 * between was dropped (replay 0), leaving the page on its spinner with the
 * header and read button hidden for good. Each flow therefore keeps the
 * latest outcome for a late subscriber, and [clear] drops it when a new
 * request starts or the page moves to another archive, so neither shows a
 * stale one.
 */
internal class DetailRequestEvents {

    private val _loaded = MutableSharedFlow<ArchiveDetail>(replay = 1)
    private val _failed = MutableSharedFlow<Exception>(replay = 1)
    private val _failedWithContent = MutableSharedFlow<Exception>(replay = 1)

    /**
     * A fresh response landed. Fires even when the content equals the
     * previous one, so the REFRESH -> NORMAL spinner transition completes.
     */
    val loaded: SharedFlow<ArchiveDetail> = _loaded.asSharedFlow()

    /** The request failed and there is nothing to show: full-screen error. */
    val failed: SharedFlow<Exception> = _failed.asSharedFlow()

    /**
     * The request failed but the page has displayable data (cache hit or
     * the degraded navigation archive): non-blocking banner.
     */
    val failedWithContent: SharedFlow<Exception> = _failedWithContent.asSharedFlow()

    /** Earlier outcomes no longer apply: a new request starts, or a new archive is shown. */
    fun clear() {
        _loaded.resetReplayCache()
        _failed.resetReplayCache()
        _failedWithContent.resetReplayCache()
    }

    fun loaded(detail: ArchiveDetail) {
        _loaded.tryEmit(detail)
    }

    fun failed(e: Exception) {
        _failed.tryEmit(e)
    }

    fun failedWithContent(e: Exception) {
        _failedWithContent.tryEmit(e)
    }
}
