package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import com.lanraragi.reader.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-04 C01: "continue to the next archive" re-issues the query of
 * the list the user tapped in. The list loads through [GalleryListDataHelper],
 * so the helper must hand the exact inputs of a landed load to the scene, and a
 * superseded load must not overwrite them. Before the fix nothing reported the
 * inputs and the reading context always carried an unfiltered default query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryListDataHelperLoadedSearchTest {

    private class RecordingCallback : GalleryListDataHelper.Callback {
        val loaded = mutableListOf<GalleryListViewModel.SearchParams>()
        override fun getHostContext(): Context? = null
        override fun getUrlBuilder(): ListUrlBuilder? = null
        override fun getSortBy(): String = "title"
        override fun getSortOrder(): String = "asc"
        override fun notifyAdapterDataSetChanged() {}
        override fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeChanged(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemMoved(fromPosition: Int, toPosition: Int) {}
        override fun showSearchBar() {}
        override fun showActionFab() {}
        override fun getString(resId: Int): String = ""
        override fun exitMultiSelect() {}
        override fun onSearchLoaded(params: GalleryListViewModel.SearchParams) {
            loaded += params
        }
    }

    private val filtered = GalleryListViewModel.SearchParams(
        filter = "artist:foo",
        category = "SET_1",
        sortby = "title",
        order = "asc",
    )

    @Test
    fun `current task reports the issued query`() {
        val callback = RecordingCallback()
        val helper = GalleryListDataHelper(callback)

        // A fresh helper's current task id is 0.
        helper.recordLoadedSearch(taskId = 0, issued = filtered)

        assertEquals(listOf(filtered), callback.loaded)
    }

    @Test
    fun `superseded task does not overwrite the query`() {
        val callback = RecordingCallback()
        val helper = GalleryListDataHelper(callback)

        helper.recordLoadedSearch(taskId = 7, issued = filtered)

        assertTrue(callback.loaded.isEmpty())
    }
}
