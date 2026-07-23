package com.hippo.ehviewer.widget

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LR Reader has no image search (the LANraragi API offers none), so
 * [SearchLayout] must expose exactly one page: the normal sort-options
 * card. The EhViewer-era image-search page and the keyword/image toggle
 * were unreachable dead code (the toggle button was hardcoded GONE) and
 * are removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SearchLayoutTest {

    private fun newSearchLayout(): SearchLayout = SearchLayout(
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme_Main)
    )

    @Test
    fun `adapter exposes only the normal search card`() {
        val layout = newSearchLayout()

        assertEquals(1, layout.adapter!!.itemCount)
    }

    @Test
    fun `default sort is date_added descending`() {
        val layout = newSearchLayout()

        assertEquals("date_added", layout.sortBy)
        assertEquals("desc", layout.sortOrder)
    }

    @Test
    fun `formatListUrlBuilder resets to a plain keyword search`() {
        val layout = newSearchLayout()
        val builder = ListUrlBuilder()
        builder.mode = ListUrlBuilder.MODE_TAG
        builder.pageIndex = 3

        layout.formatListUrlBuilder(builder, "some query")

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals("some query", builder.keyword)
        assertEquals(0, builder.pageIndex)
    }
}
