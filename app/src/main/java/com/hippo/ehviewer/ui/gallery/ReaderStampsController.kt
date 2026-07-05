package com.hippo.ehviewer.ui.gallery

import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRStampApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Per-archive stamps state: support probing, lazy cache, pessimistic CRUD.
 *
 * INDEXING: the cache and all `page1` params are 1-indexed (server truth,
 * the stamp key embeds the page). Only [stampsForDisplayPage],
 * [addStamp] and [ensureVisiblePagesLoaded] accept the reader's 0-indexed
 * pages and convert at this boundary.
 *
 * Threading: all public methods are main-thread only ([scope] is the
 * Activity's lifecycleScope, Main-dispatched); the backend suspends to IO
 * internally. [onDataChanged] fires on main after any state/cache change.
 */
class ReaderStampsController(
    private val scope: CoroutineScope,
    private val backend: StampsBackend,
    private val onDataChanged: () -> Unit,
) {

    enum class Support { UNKNOWN, SUPPORTED, UNSUPPORTED }

    var support: Support = Support.UNKNOWN
        private set

    /** Session-scoped overlay visibility, latched by explicit stamp actions. */
    var sessionVisible: Boolean = false

    private var stampedPages: MutableSet<Int>? = null
    private val pageStamps = mutableMapOf<Int, List<LRRStampApi.StampData>>()
    private val loadingPages = mutableSetOf<Int>()
    private var loadingIndex = false

    fun stampedPagesSorted(): List<Int>? = stampedPages?.sorted()

    fun stampsForDisplayPage(page0: Int): List<LRRStampApi.StampData> =
        pageStamps[page0 + 1].orEmpty()

    fun previewForPage(page1: Int): String? =
        pageStamps[page1]?.firstOrNull()?.content

    fun refreshIndex(onResult: ((Boolean) -> Unit)? = null) {
        if (support == Support.UNSUPPORTED || loadingIndex) return
        loadingIndex = true
        scope.launch {
            try {
                val fetched = backend.stampedPages().toMutableSet()
                // Reconcile with local truth: pageStamps holds server-confirmed
                // state for every page it contains (pessimistic updates), so a
                // slow fetch resolving AFTER an interleaved add/delete must not
                // undo it — pages we know have stamps (in-flight adds) and pages
                // we know are now empty (in-flight deletes) win over the snapshot.
                for ((page1, stamps) in pageStamps) {
                    if (stamps.isEmpty()) fetched.remove(page1) else fetched.add(page1)
                }
                stampedPages = fetched
                support = Support.SUPPORTED
                onDataChanged()
                onResult?.invoke(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LRRHttpException) {
                if (e.code == 404) {
                    // Route absent: pre-0.9.8 server. Disable for this reader session.
                    support = Support.UNSUPPORTED
                    onDataChanged()
                }
                onResult?.invoke(false)
            } catch (e: Exception) {
                // Transient (offline etc.): stay retryable.
                if (BuildConfig.DEBUG) Log.w(TAG, "Stamp index refresh failed", e)
                onResult?.invoke(false)
            } finally {
                loadingIndex = false
            }
        }
    }

    /** [pages0] = currently visible 0-indexed pages; loads details for stamped ones. */
    fun ensureVisiblePagesLoaded(pages0: Collection<Int>) {
        val index = stampedPages
        if (index == null) {
            if (support != Support.UNSUPPORTED) refreshIndex()
            return
        }
        for (page0 in pages0) {
            val page1 = page0 + 1
            if (page1 in index && page1 !in pageStamps && page1 !in loadingPages) {
                loadPage(page1)
            }
        }
    }

    /** Preload details for 1-indexed [pages1] (navigation dialog previews). */
    fun ensurePagesLoaded(pages1: Collection<Int>, onEachLoaded: (() -> Unit)? = null) {
        for (page1 in pages1) {
            if (page1 !in pageStamps && page1 !in loadingPages) {
                loadPage(page1, onEachLoaded)
            }
        }
    }

    private fun loadPage(page1: Int, onLoaded: (() -> Unit)? = null) {
        loadingPages += page1
        scope.launch {
            try {
                pageStamps[page1] = backend.stampsByPage(page1)
                onDataChanged()
                onLoaded?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transient; retried on the next visible pass.
                if (BuildConfig.DEBUG) Log.w(TAG, "Stamp page load failed for page1=$page1", e)
            } finally {
                loadingPages -= page1
            }
        }
    }

    fun addStamp(
        page0: Int,
        content: String,
        normX: Float,
        normY: Float,
        onResult: (Throwable?) -> Unit,
    ) {
        val page1 = page0 + 1
        val position = StampGeometry.formatPosition(normX, normY)
        scope.launch {
            try {
                val id = backend.add(page1, content, position)
                pageStamps[page1] = pageStamps[page1].orEmpty() +
                    LRRStampApi.StampData(id = id, position = position, content = content)
                stampedPages?.add(page1)
                onDataChanged()
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e)
            }
        }
    }

    /**
     * Callers normally hold a warm cache for [page1] — the marker being edited
     * was rendered from it — and the entry is rewritten in place. On the cold
     * path (no cached entry carries [stampId]) the page is backfilled from the
     * server after the write succeeds instead of leaving the cache stale.
     */
    fun updateStamp(
        page1: Int,
        stampId: String,
        content: String? = null,
        position: String? = null,
        onResult: (Throwable?) -> Unit,
    ) {
        scope.launch {
            try {
                backend.update(stampId, content, position)
                val cached = pageStamps[page1].orEmpty()
                if (cached.none { it.id == stampId }) {
                    loadPage(page1)
                } else {
                    pageStamps[page1] = cached.map {
                        if (it.id == stampId) {
                            it.copy(
                                content = content ?: it.content,
                                position = position ?: it.position,
                            )
                        } else {
                            it
                        }
                    }
                    onDataChanged()
                }
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e)
            }
        }
    }

    fun deleteStamp(page1: Int, stampId: String, onResult: (Throwable?) -> Unit) {
        scope.launch {
            try {
                backend.delete(stampId)
                val remaining = pageStamps[page1].orEmpty().filterNot { it.id == stampId }
                pageStamps[page1] = remaining
                if (remaining.isEmpty()) stampedPages?.remove(page1)
                onDataChanged()
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e)
            }
        }
    }

    private companion object {
        const val TAG = "ReaderStampsController"
    }
}
