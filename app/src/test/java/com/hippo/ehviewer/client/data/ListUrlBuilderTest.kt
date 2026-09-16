package com.hippo.ehviewer.client.data

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the Parcelable wire format and copy semantics of [ListUrlBuilder]
 * while the EhViewer-era image-search fields are removed: every field that
 * is written must be read back in the same order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ListUrlBuilderTest {

    private fun populated(): ListUrlBuilder = ListUrlBuilder().apply {
        mode = ListUrlBuilder.MODE_TAG
        pageIndex = 7
        category = 3
        keyword = "artist:someone"
        advanceSearch = 5
        minRating = 4
        pageFrom = 10
        pageTo = 20
        categoryId = "SET_1704939135"
        categoryName = "Favourites"
    }

    @Test
    fun `parcel round-trip preserves every field`() {
        val original = populated()

        val parcel = Parcel.obtain()
        val restored = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ListUrlBuilder.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }

        assertEquals(original.mode, restored.mode)
        assertEquals(original.pageIndex, restored.pageIndex)
        assertEquals(original.category, restored.category)
        assertEquals(original.keyword, restored.keyword)
        assertEquals(original.advanceSearch, restored.advanceSearch)
        assertEquals(original.minRating, restored.minRating)
        assertEquals(original.pageFrom, restored.pageFrom)
        assertEquals(original.pageTo, restored.pageTo)
        assertEquals("SET_1704939135", restored.categoryId)
        assertEquals("Favourites", restored.categoryName)
    }

    @Test
    fun `set copies every field from the template`() {
        val template = populated()
        val target = ListUrlBuilder()

        target.set(template)

        assertEquals(template.mode, target.mode)
        assertEquals(template.pageIndex, target.pageIndex)
        assertEquals(template.category, target.category)
        assertEquals(template.keyword, target.keyword)
        assertEquals(template.advanceSearch, target.advanceSearch)
        assertEquals(template.minRating, target.minRating)
        assertEquals(template.pageFrom, target.pageFrom)
        assertEquals(template.pageTo, target.pageTo)
        assertEquals("SET_1704939135", target.categoryId)
        assertEquals("Favourites", target.categoryName)
    }

    @Test
    fun `reset returns to the homepage state`() {
        val builder = populated()

        builder.reset()

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals(0, builder.pageIndex)
        assertEquals(null, builder.keyword)
        assertEquals(-1, builder.advanceSearch)
        assertNull(builder.categoryId)
        assertNull(builder.categoryName)
    }

    @Test
    fun `setKeywordKeepingCategory resets everything except the category`() {
        val builder = populated()

        builder.setKeywordKeepingCategory("foo")

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals("foo", builder.keyword)
        assertEquals("SET_1704939135", builder.categoryId)
        assertEquals("Favourites", builder.categoryName)
        assertEquals(0, builder.pageIndex)
        assertEquals(-1, builder.advanceSearch)
        assertEquals(-1, builder.minRating)
        assertEquals(-1, builder.pageFrom)
        assertEquals(-1, builder.pageTo)
    }

    @Test
    fun `fresh-search setters drop the category`() {
        val tagSearch = populated()
        tagSearch.set("artist:other")
        assertNull(tagSearch.categoryId)
        assertNull(tagSearch.categoryName)

        val modeSearch = populated()
        modeSearch.set("uploader", ListUrlBuilder.MODE_UPLOADER)
        assertNull(modeSearch.categoryId)
        assertNull(modeSearch.categoryName)
    }

    @Test
    fun `setKeywordKeepingCategory with no category behaves like a fresh normal search`() {
        val builder = populated().apply { categoryId = null; categoryName = null }

        builder.setKeywordKeepingCategory("bar")

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals("bar", builder.keyword)
        assertNull(builder.categoryId)
        assertNull(builder.categoryName)
    }
}
