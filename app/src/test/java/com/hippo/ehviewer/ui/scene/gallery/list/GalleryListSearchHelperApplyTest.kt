package com.hippo.ehviewer.ui.scene.gallery.list

import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks what applying a typed query from the normal list state does to the
 * builder (spec 2026-09-15, Q1): the category survives so the server keeps
 * filtering within it, subscription mode survives as before, everything
 * else resets. The mutation is a pure companion function because the full
 * onApplySearch path drives an attached ContentLayout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryListSearchHelperApplyTest {

    @Test
    fun `query inside a category keeps the category`() {
        val builder = ListUrlBuilder().apply {
            categoryId = "SET_1"; categoryName = "Favourites"; pageIndex = 4
        }

        GalleryListSearchHelper.applyQuery(builder, "touhou")

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals("touhou", builder.keyword)
        assertEquals("SET_1", builder.categoryId)
        assertEquals("Favourites", builder.categoryName)
        assertEquals(0, builder.pageIndex)
    }

    @Test
    fun `empty query inside a category returns to the whole category`() {
        val builder = ListUrlBuilder().apply { categoryId = "SET_1"; keyword = "touhou" }

        GalleryListSearchHelper.applyQuery(builder, "")

        assertEquals("", builder.keyword)
        assertEquals("SET_1", builder.categoryId)
    }

    @Test
    fun `query without a category is a plain normal search`() {
        val builder = ListUrlBuilder().apply { mode = ListUrlBuilder.MODE_TAG; keyword = "old" }

        GalleryListSearchHelper.applyQuery(builder, "new")

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals("new", builder.keyword)
        assertNull(builder.categoryId)
    }

    @Test
    fun `subscription mode is preserved`() {
        val builder = ListUrlBuilder().apply { mode = ListUrlBuilder.MODE_SUBSCRIPTION }

        GalleryListSearchHelper.applyQuery(builder, "touhou")

        assertEquals(ListUrlBuilder.MODE_SUBSCRIPTION, builder.mode)
        assertEquals("touhou", builder.keyword)
    }
}
