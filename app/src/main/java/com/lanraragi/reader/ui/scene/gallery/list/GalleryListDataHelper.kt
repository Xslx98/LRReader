package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.ui.list.ListDiffBinder
import com.lanraragi.reader.ui.list.ListScreenState
import com.lanraragi.reader.widget.ListRefreshLayout
import com.lanraragi.reader.widget.StateListLayout
import com.lanraragi.reader.widget.list.LayoutManagerUtils

/**
 * The gallery list's view side: renders [GalleryListViewModel.listState] into
 * the scene's [StateListLayout] and adapter, turns the refresh gestures into
 * loads, and is the one place the scene's helpers read and edit the rows
 * through ([data], [removeAt], [addAt], [replaceAt]). The paging state itself
 * lives in the ViewModel ([GalleryListState]); nothing here survives the view.
 */
class GalleryListDataHelper(
    private val callback: Callback,
    private val viewModel: GalleryListViewModel,
) {

    interface Callback {
        fun getHostContext(): Context?

        /** The scene's query as of now (search bar, category, sort, tank mode). */
        fun currentQuery(): GalleryListQuery
        fun showSearchBar()
        fun showActionFab()
        fun getString(resId: Int): String

        /** Leave multi-select mode (no-op when it is not active). */
        fun exitMultiSelect()

        /** Settle an in-flight merge-into-tankoubon animation before positions change. */
        fun cancelTankMerge() {}
    }

    /** What the adapter shows. */
    private val shown = ListDiffBinder<Archive>(MAX_DIFF) { old, new -> RowDiff(old, new) }

    private var stateList: StateListLayout? = null
    private var refreshLayout: ListRefreshLayout? = null
    private var recyclerView: RecyclerView? = null
    private var nextPageScroll = 0

    /** The state rendered last (to tell which load just landed). */
    private var rendered: GalleryListState? = null

    /** The rows on screen, in adapter order. Edit them through [removeAt] / [addAt] / [replaceAt]. */
    val data: List<Archive> get() = shown.items

    fun size(): Int = shown.items.size

    fun getDataAtEx(location: Int): Archive? = shown.items.getOrNull(location)

    /**
     * Binds the list's views. The rows the ViewModel already holds are shown
     * at once, before the view state is restored, so a rebuilt view keeps its
     * scroll position.
     */
    fun attach(
        stateList: StateListLayout,
        refreshLayout: ListRefreshLayout,
        recyclerView: RecyclerView,
        adapter: RecyclerView.Adapter<*>,
    ) {
        this.stateList = stateList
        this.refreshLayout = refreshLayout
        this.recyclerView = recyclerView
        nextPageScroll = (NEXT_PAGE_SCROLL_DP * recyclerView.resources.displayMetrics.density).toInt()
        shown.submit(viewModel.listState.value.pages.rows)
        shown.adapter = adapter
        stateList.onRetry = { refresh() }
        refreshLayout.setOnRefreshListener(object : ListRefreshLayout.OnRefreshListener {
            override fun onHeaderRefresh() = load(GalleryLoadKind.Pull)

            override fun onFooterRefresh() {
                // At the end, pulling up loads the last page again (new archives).
                load(if (viewModel.listState.value.pages.canLoadMore) GalleryLoadKind.NextPage else GalleryLoadKind.LastPage)
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layout = this@GalleryListDataHelper.refreshLayout ?: return
                if (!layout.isAnyRefreshing && layout.isAlmostBottom() &&
                    viewModel.listState.value.pages.canLoadMore
                ) {
                    layout.setFooterRefreshing(true)
                    load(GalleryLoadKind.NextPage)
                }
            }
        })
        rendered = null
    }

    /** The view is gone: nothing is dispatched until the next [attach]. */
    fun detach() {
        shown.adapter = null
        stateList = null
        refreshLayout = null
        recyclerView = null
    }

    // ==================== Loads ====================

    /** The screen's first load: only when the ViewModel has nothing yet (a rotation keeps the list). */
    fun firstRefresh() = viewModel.loadListIfIdle(callback.currentQuery())

    /** Loads the query again from page 0, showing the skeleton. */
    fun refresh() = load(GalleryLoadKind.Refresh)

    private fun load(kind: GalleryLoadKind) {
        // Any load that replaces rows invalidates adapter positions, so an
        // in-flight multi-select (or merge animation) must not survive it.
        // Only next-page appends keep existing positions valid.
        if (kind != GalleryLoadKind.NextPage) {
            callback.exitMultiSelect()
            callback.cancelTankMerge()
        }
        viewModel.loadList(kind, callback.currentQuery())
    }

    // ==================== Rendering ====================

    /** Shows [state]; called for every state the ViewModel publishes while the view is up. */
    fun render(state: GalleryListState) {
        val before = rendered
        rendered = state
        val landed = (before as? GalleryListState.Loading)?.kind?.takeIf { state !is GalleryListState.Loading }
        val oldSize = shown.items.size
        shown.submit(state.pages.rows)

        refreshLayout?.apply {
            val loading = (state as? GalleryListState.Loading)?.kind
            setHeaderRefreshing(loading == GalleryLoadKind.Pull)
            setFooterRefreshing(loading == GalleryLoadKind.NextPage || loading == GalleryLoadKind.LastPage)
        }
        val list = stateList
        if (list != null) {
            list.emptyText = callback.getString(state.pages.emptyText)
            val screen = screenOf(state)
            if (before == null || screenOf(before)::class != screen::class) {
                // A different slot comes up: bring the chrome back with it.
                callback.showSearchBar()
                callback.showActionFab()
            }
            list.render(screen)
        }
        if (state is GalleryListState.Content && landed != null) scrollAfter(landed, oldSize, state.pages)
    }

    /** Where the list goes after a load of [kind] landed. */
    private fun scrollAfter(kind: GalleryLoadKind, oldSize: Int, pages: GalleryPages) {
        val view = recyclerView ?: return
        if (!view.isAttachedToWindow) return
        val manager = view.layoutManager ?: return
        when (kind) {
            GalleryLoadKind.NextPage -> if (pages.rows.size > oldSize) {
                view.stopScroll()
                view.smoothScrollBy(0, nextPageScroll)
            }
            GalleryLoadKind.LastPage -> if (pages.rows.size > oldSize) {
                view.stopScroll()
                LayoutManagerUtils.scrollToPositionWithOffset(manager, pages.rows.size - 1, 0)
            }
            else -> {
                view.stopScroll()
                LayoutManagerUtils.scrollToPositionWithOffset(manager, 0, 0)
                onScrollToPosition(0)
            }
        }
    }

    /** The search bar and FAB come back whenever the list is scrolled to the top. */
    fun onScrollToPosition(position: Int) {
        if (position == 0) {
            callback.showSearchBar()
            callback.showActionFab()
        }
    }

    /** Toasts the failure of a load that kept the rows on screen. */
    fun showNotice(message: String) {
        callback.getHostContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    // ==================== Edits ====================

    /** Removes row [index] (a deletion while the list is shown). */
    fun removeAt(index: Int) {
        if (index !in shown.items.indices) return
        viewModel.editList { it.removeAt(index) }
        shown.edit(viewModel.listState.value.pages.rows) { notifyItemRemoved(index) }
    }

    /** Inserts [row] at [index] (a merge's provisional tankoubon). */
    fun addAt(index: Int, row: Archive) {
        val at = index.coerceIn(0, shown.items.size)
        viewModel.editList { it.insertAt(at, row) }
        shown.edit(viewModel.listState.value.pages.rows) { notifyItemInserted(at) }
    }

    /** Replaces row [index] in place: a change (crossfade), not a remove + insert. */
    fun replaceAt(index: Int, row: Archive) {
        if (index !in shown.items.indices) return
        viewModel.editList { it.replaceAt(index, row) }
        shown.edit(viewModel.listState.value.pages.rows) { notifyItemChanged(index) }
    }

    private class RowDiff(private val old: List<Archive>, private val new: List<Archive>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].arcid == new[n].arcid
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }

    companion object {
        /** Past this many changed rows a reload is remove + insert instead of a diff (see ListDiffBinder). */
        private const val MAX_DIFF = 200

        /** How far the list scrolls on when a next page lands, so the new rows show. */
        private const val NEXT_PAGE_SCROLL_DP = 48f

        /**
         * The [StateListLayout] slot for [state]: the skeleton while a
         * (re)load hides the rows, the rows while they stay, the error or the
         * empty wording.
         */
        internal fun screenOf(state: GalleryListState): ListScreenState<Archive> = when (state) {
            GalleryListState.Idle -> ListScreenState.Loading
            is GalleryListState.Loading ->
                if (state.kind.keepsRows && state.pages.rows.isNotEmpty()) {
                    ListScreenState.Content(state.pages.rows)
                } else {
                    ListScreenState.Loading
                }
            is GalleryListState.Content -> ListScreenState.Content(state.pages.rows)
            is GalleryListState.Empty -> ListScreenState.Empty
            is GalleryListState.Error -> ListScreenState.Error(state.message, emptyList())
        }
    }
}
