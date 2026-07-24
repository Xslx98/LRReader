package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

/**
 * Regression test for UI-6: a list load that fails after the hosting Scene has
 * already been popped must not crash the app.
 *
 * The paging load is launched on the process-level ioScope and outlives the
 * Scene, so a slow failure (up to the OkHttp timeout) can be delivered when
 * [GalleryListDataHelper.Callback.getHostContext] already returns null. The
 * framework `ContentHelper.onGetException` then dereferences that null context
 * (`friendlyError(...)` / `Toast.makeText(null, ...)`) on the main thread and
 * throws a NullPointerException. The fix drops the stale failure at the business
 * funnel ([GalleryListDataHelper] `onGetFailure`) whenever there is no host.
 *
 * The helper here is intentionally left un-attached (no host ContentLayout),
 * standing in for a detached Scene: both share the invariant that the framework
 * error UI must never run without a live host context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryListDataHelperFailureTest {

    /** Simulates a popped Scene: the host context is gone. */
    private class NullContextCallback : GalleryListDataHelper.Callback {
        override fun getHostContext(): Context? = null
        override fun getUrlBuilder(): ListUrlBuilder? = null
        override fun getSortBy(): String = "date_added"
        override fun getSortOrder(): String = "desc"
        override fun notifyAdapterDataSetChanged() {}
        override fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeChanged(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemMoved(fromPosition: Int, toPosition: Int) {}
        override fun showSearchBar() {}
        override fun showActionFab() {}
        override fun getString(resId: Int): String = "no servers"
        override fun exitMultiSelect() {}
    }

    @Test
    fun failureDeliveredAfterSceneDetached_doesNotCrash() {
        val helper = GalleryListDataHelper(NullContextCallback())

        // getPageData is protected (inherited from the Java framework), so drive
        // it via reflection. A fresh helper has mCurrentTaskId == 0, so task id 0
        // is the "current" task; with no server configured, getPageData funnels
        // synchronously through onGetFailure -> framework onGetException -- exactly
        // the path that crashes when the host context is null.
        val getPageData = GalleryListDataHelper::class.java
            .getDeclaredMethod("getPageData", Int::class.java, Int::class.java, Int::class.java)
            .apply { isAccessible = true }

        try {
            getPageData.invoke(helper, 0, /* type = TYPE_REFRESH */ 0, /* page = */ 0)
        } catch (e: InvocationTargetException) {
            fail("A failure delivered after the Scene is detached must be dropped, but threw: ${e.cause}")
        }
    }
}
