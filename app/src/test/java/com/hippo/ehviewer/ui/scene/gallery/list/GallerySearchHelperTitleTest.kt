package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the search-bar title for category browsing (spec 2026-09-15, Q6):
 * the category name alone, `"<name> · <keyword>"` once a query is applied
 * inside it, and the raw id only as a fallback when no name is known
 * (rows migrated from the legacy keyword protocol). Non-category titles
 * are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GallerySearchHelperTitleTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun title(builder: ListUrlBuilder): String? =
        GallerySearchHelper.getSuitableTitleForUrlBuilder(resources, builder, true)

    @Test
    fun `category with a name and no keyword shows the name`() {
        val builder = ListUrlBuilder().apply { categoryId = "SET_1"; categoryName = "Favourites" }

        assertEquals("Favourites", title(builder))
    }

    @Test
    fun `category with a keyword shows name dot keyword`() {
        val builder = ListUrlBuilder().apply {
            categoryId = "SET_1"; categoryName = "Favourites"; keyword = "touhou"
        }

        assertEquals("Favourites · touhou", title(builder))
    }

    @Test
    fun `category without a name falls back to the id`() {
        val builder = ListUrlBuilder().apply { categoryId = "SET_1" }

        assertEquals("SET_1", title(builder))
        builder.keyword = "touhou"
        assertEquals("SET_1 · touhou", title(builder))
    }

    @Test
    fun `blank keyword inside a category still shows just the name`() {
        val builder = ListUrlBuilder().apply {
            categoryId = "SET_1"; categoryName = "Favourites"; keyword = "  "
        }

        assertEquals("Favourites", title(builder))
    }

    @Test
    fun `keyword without a category is unchanged`() {
        assertEquals("touhou", title(ListUrlBuilder().apply { keyword = "touhou" }))
    }

    @Test
    fun `plain homepage is unchanged`() {
        assertEquals(resources.getString(R.string.app_name), title(ListUrlBuilder()))
    }

    @Test
    fun `category in subscription mode is still the category, not the subscription title`() {
        val builder = ListUrlBuilder().apply {
            mode = ListUrlBuilder.MODE_SUBSCRIPTION; categoryId = "SET_1"; categoryName = "Favourites"
        }

        assertEquals("Favourites", title(builder))
    }
}
