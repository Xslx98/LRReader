package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import android.content.res.Resources
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ImageSpan
import com.lanraragi.reader.R
import com.lanraragi.reader.archive.LRRUtils
import com.lanraragi.reader.archive.ListUrlBuilder
import com.lanraragi.reader.widget.SearchBar
import androidx.core.content.ContextCompat

/**
 * Stateless search helpers for GalleryListScene: list titles, tag keyword
 * quoting and the search bar hint.
 */
object GallerySearchHelper {
    /**
     * Compute a human-readable title for the given ListUrlBuilder.
     * Returns null if no suitable title can be determined.
     */
    fun getSuitableTitleForUrlBuilder(
        resources: Resources,
        urlBuilder: ListUrlBuilder,
        appName: Boolean
    ): String? {
        val keyword = urlBuilder.keyword
        val category = urlBuilder.category
        val categoryId = urlBuilder.categoryId

        return when {
            // Category browsing: the category name (id only as a fallback for rows
            // migrated without one), "name · keyword" once a query is applied inside it.
            categoryId != null -> {
                val name = urlBuilder.categoryName?.takeIf { it.isNotBlank() } ?: categoryId
                val trimmed = keyword?.trim()
                if (trimmed.isNullOrEmpty()) {
                    name
                } else {
                    resources.getString(R.string.search_title_in_category, name, trimmed)
                }
            }

            urlBuilder.mode == ListUrlBuilder.MODE_NORMAL &&
                    LRRUtils.NONE == category &&
                    TextUtils.isEmpty(keyword) &&
                    urlBuilder.advanceSearch == -1 &&
                    urlBuilder.minRating == -1 &&
                    urlBuilder.pageFrom == -1 &&
                    urlBuilder.pageTo == -1 ->
                resources.getString(if (appName) R.string.app_name else R.string.homepage)

            urlBuilder.mode == ListUrlBuilder.MODE_SUBSCRIPTION &&
                    LRRUtils.NONE == category &&
                    TextUtils.isEmpty(keyword) &&
                    urlBuilder.advanceSearch == -1 &&
                    urlBuilder.minRating == -1 &&
                    urlBuilder.pageFrom == -1 &&
                    urlBuilder.pageTo == -1 ->
                resources.getString(R.string.subscription)

            urlBuilder.mode == ListUrlBuilder.MODE_WHATS_HOT ->
                resources.getString(R.string.whats_hot)

            !TextUtils.isEmpty(keyword) -> keyword

            category.countOneBits() == 1 -> LRRUtils.getCategory(category)

            else -> null
        }
    }

    /**
     * Wrap a tag keyword with quotes if it contains a namespace and spaces.
     * E.g. "artist:some name" becomes "artist:\"some name$\"".
     */
    fun wrapTagKeyword(keyword: String): String {
        val trimmed = keyword.trim()

        val index1 = trimmed.indexOf(':')
        if (index1 == -1 || index1 >= trimmed.length - 1) {
            // Can't find :, or : is the last char
            return trimmed
        }
        if (trimmed[index1 + 1] == '"') {
            // The char after : is ", the word must be quoted
            return trimmed
        }
        val index2 = trimmed.indexOf(' ')
        if (index2 <= index1) {
            // Can't find space, or space is before :
            return trimmed
        }

        return trimmed.substring(0, index1 + 1) + "\"" + trimmed.substring(index1 + 1) + "$\""
    }

    /**
     * Configure the search bar hint with the magnify icon and site-specific text.
     */
    fun setSearchBarHint(context: Context, searchBar: SearchBar) {
        val resources = context.resources
        val searchImage = ContextCompat.getDrawable(context, R.drawable.v_magnify_x24)
        val ssb = SpannableStringBuilder("   ")
        ssb.append(resources.getString(R.string.gallery_list_search_bar_hint))
        val textSize = (searchBar.editTextTextSize * 1.25).toInt()
        if (searchImage != null) {
            searchImage.setBounds(0, 0, textSize, textSize)
            ssb.setSpan(ImageSpan(searchImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        searchBar.setEditTextHint(ssb)
    }
}
