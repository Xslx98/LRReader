package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.module.CoroutineModule
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A new list load cancels the one it supersedes instead of letting its
 * `/api/search` request and parse run to a result nobody uses (audit
 * 2026-10-13 PERF-12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryListDataHelperSupersedeTest {

    private object NoopCallback : GalleryListDataHelper.Callback {
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
        override fun onSearchLoaded(params: GalleryListViewModel.SearchParams) {}
    }

    @Test
    fun aNewLoadCancelsTheOneItSupersedes() {
        ServiceRegistry.initializeForTest(CoroutineModule())
        val helper = GalleryListDataHelper(NoopCallback)

        val first = helper.replaceLoad { awaitCancellation() }
        val second = helper.replaceLoad { awaitCancellation() }

        awaitUntil(message = "superseded load still running") { first.isCancelled }
        assertTrue(second.isActive)
        second.cancel()
    }
}
