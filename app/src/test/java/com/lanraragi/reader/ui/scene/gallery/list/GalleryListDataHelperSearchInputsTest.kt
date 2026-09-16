package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the builder → `/api/search` parameter mapping (spec 2026-09-15):
 * [ListUrlBuilder.categoryId] is the `category` parameter and the keyword is
 * plain `filter` text — both may be sent together (search within a category).
 * The retired `"category:<id>"` keyword protocol gets no runtime tolerance
 * (Q3): such a keyword is ordinary filter text now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryListDataHelperSearchInputsTest {

    @Test
    fun `category only sends the id and no filter`() {
        val inputs = GalleryListDataHelper.searchInputsFor(
            ListUrlBuilder().apply { categoryId = "SET_1"; categoryName = "Favourites" }
        )

        assertEquals("SET_1", inputs.categoryId)
        assertNull(inputs.filter)
    }

    @Test
    fun `keyword only sends the filter and no category`() {
        val inputs = GalleryListDataHelper.searchInputsFor(ListUrlBuilder().apply { keyword = "touhou" })

        assertEquals("touhou", inputs.filter)
        assertNull(inputs.categoryId)
    }

    @Test
    fun `keyword inside a category sends both`() {
        val inputs = GalleryListDataHelper.searchInputsFor(
            ListUrlBuilder().apply { categoryId = "SET_1"; keyword = "touhou" }
        )

        assertEquals("SET_1", inputs.categoryId)
        assertEquals("touhou", inputs.filter)
    }

    @Test
    fun `blank keyword is no filter`() {
        val inputs = GalleryListDataHelper.searchInputsFor(ListUrlBuilder().apply { keyword = "   " })

        assertNull(inputs.filter)
    }

    @Test
    fun `legacy category-prefixed keyword is plain filter text now`() {
        val inputs = GalleryListDataHelper.searchInputsFor(ListUrlBuilder().apply { keyword = "category:SET_1" })

        assertEquals("category:SET_1", inputs.filter)
        assertNull(inputs.categoryId)
    }

    @Test
    fun `null builder sends nothing`() {
        val inputs = GalleryListDataHelper.searchInputsFor(null)

        assertNull(inputs.filter)
        assertNull(inputs.categoryId)
    }
}
