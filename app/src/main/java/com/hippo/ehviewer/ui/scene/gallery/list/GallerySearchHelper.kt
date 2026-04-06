package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ImageSpan
import android.widget.TextView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.lrr.data.LRRSearchResult
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.scene.Announcer
import com.hippo.util.DrawableManager
import kotlin.math.ceil

/**
 * Handles search-related utility logic for GalleryListScene.
 * Contains URL suggestion classes, search bar configuration helpers,
 * keyword processing, title computation, and LRR search result conversion.
 */
class GallerySearchHelper(private val mCallback: Callback) {

    /**
     * Callback for operations that require scene interaction
     * (navigation, state management, context access).
     */
    interface Callback {
        fun getHostContext(): Context?
        fun getHostResources(): Resources?
        fun navigateToScene(announcer: Announcer)
        fun getSearchState(): Int
        fun setSearchState(state: Int)
    }

    /**
     * Result holder for LRR search result conversion.
     */
    class LRRPaginatedResult(
        @JvmField val galleryInfoList: List<GalleryInfo>,
        @JvmField val totalPages: Int,
        @JvmField val nextPage: Int
    )

    /**
     * Create a SuggestionProvider that parses gallery/page URLs from search text
     * and returns navigation suggestions.
     */
    fun createSuggestionProvider(): SearchBar.SuggestionProvider {
        // LANraragi: E-Hentai URL parsing removed — no URL-based suggestions
        return object : SearchBar.SuggestionProvider {
            override fun providerSuggestions(text: String): List<SearchBar.Suggestion>? = null
        }
    }

    // -----------------------------------------------------------------------
    // Suggestion inner classes
    // -----------------------------------------------------------------------

    /**
     * Base suggestion class for URL-based gallery navigation.
     */
    internal abstract class UrlSuggestion(private val mCallback: Callback) : SearchBar.Suggestion() {

        override fun getText(textSize: Float): CharSequence? {
            val context = mCallback.getHostContext() ?: return null
            val resources = mCallback.getHostResources() ?: return null
            val bookImage = DrawableManager.getVectorDrawable(context, R.drawable.v_book_open_x24)
            val ssb = SpannableStringBuilder("    ")
            ssb.append(resources.getString(R.string.gallery_list_search_bar_open_gallery))
            val imageSize = (textSize * 1.25).toInt()
            if (bookImage != null) {
                bookImage.setBounds(0, 0, imageSize, imageSize)
                ssb.setSpan(ImageSpan(bookImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return ssb
        }

        override fun onClick() {
            mCallback.navigateToScene(createAnnouncer())

            val state = mCallback.getSearchState()
            if (state == GalleryListScene.STATE_SIMPLE_SEARCH) {
                mCallback.setSearchState(GalleryListScene.STATE_NORMAL)
            } else if (state == GalleryListScene.STATE_SEARCH_SHOW_LIST) {
                mCallback.setSearchState(GalleryListScene.STATE_SEARCH)
            }
        }

        abstract fun createAnnouncer(): Announcer

        override fun onLongClick() {}
    }

    /**
     * Suggestion for navigating to a gallery detail page by GID + token.
     */
    internal class GalleryDetailUrlSuggestion(
        callback: Callback,
        private val mGid: Long,
        private val mToken: String
    ) : UrlSuggestion(callback) {

        override fun createAnnouncer(): Announcer {
            val args = Bundle()
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
            args.putLong(GalleryDetailScene.KEY_GID, mGid)
            args.putString(GalleryDetailScene.KEY_TOKEN, mToken)
            return Announcer(GalleryDetailScene::class.java).setArgs(args)
        }

        override fun getText(textView: TextView): CharSequence? = null
    }

    /**
     * Suggestion for navigating to a gallery page by GID + page token + page number.
     */
    internal class GalleryPageUrlSuggestion(
        callback: Callback,
        private val mGid: Long,
        private val mPToken: String,
        private val mPage: Int
    ) : UrlSuggestion(callback) {

        override fun createAnnouncer(): Announcer {
            val args = Bundle()
            args.putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN)
            args.putLong(ProgressScene.KEY_GID, mGid)
            args.putString(ProgressScene.KEY_PTOKEN, mPToken)
            args.putInt(ProgressScene.KEY_PAGE, mPage)
            return Announcer(ProgressScene::class.java).setArgs(args)
        }

        override fun getText(textView: TextView): CharSequence? = null
    }

    companion object {
        /**
         * Compute a human-readable title for the given ListUrlBuilder.
         * Returns null if no suitable title can be determined.
         */
        @JvmStatic
        fun getSuitableTitleForUrlBuilder(
            resources: Resources,
            urlBuilder: ListUrlBuilder,
            appName: Boolean
        ): String? {
            val keyword = urlBuilder.keyword
            val category = urlBuilder.category

            return when {
                urlBuilder.mode == ListUrlBuilder.MODE_NORMAL &&
                        EhUtils.NONE == category &&
                        TextUtils.isEmpty(keyword) &&
                        urlBuilder.advanceSearch == -1 &&
                        urlBuilder.minRating == -1 &&
                        urlBuilder.pageFrom == -1 &&
                        urlBuilder.pageTo == -1 ->
                    resources.getString(if (appName) R.string.app_name else R.string.homepage)

                urlBuilder.mode == ListUrlBuilder.MODE_SUBSCRIPTION &&
                        EhUtils.NONE == category &&
                        TextUtils.isEmpty(keyword) &&
                        urlBuilder.advanceSearch == -1 &&
                        urlBuilder.minRating == -1 &&
                        urlBuilder.pageFrom == -1 &&
                        urlBuilder.pageTo == -1 ->
                    resources.getString(R.string.subscription)

                urlBuilder.mode == ListUrlBuilder.MODE_WHATS_HOT ->
                    resources.getString(R.string.whats_hot)

                !TextUtils.isEmpty(keyword) -> keyword

                MathUtils.hammingWeight(category) == 1 -> EhUtils.getCategory(category)

                else -> null
            }
        }

        /**
         * Wrap a tag keyword with quotes if it contains a namespace and spaces.
         * E.g. "artist:some name" becomes "artist:\"some name$\"".
         */
        @JvmStatic
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
        @JvmStatic
        fun setSearchBarHint(context: Context, searchBar: SearchBar) {
            val resources = context.resources
            val searchImage = DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24)
            val ssb = SpannableStringBuilder("   ")
            ssb.append(
                resources.getString(
                    if (EhUrl.SITE_EX == AppearanceSettings.getGallerySite())
                        R.string.gallery_list_search_bar_hint_exhentai
                    else
                        R.string.gallery_list_search_bar_hint_e_hentai
                )
            )
            val textSize = (searchBar.editTextTextSize * 1.25).toInt()
            if (searchImage != null) {
                searchImage.setBounds(0, 0, textSize, textSize)
                ssb.setSpan(ImageSpan(searchImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            searchBar.setEditTextHint(ssb)
        }

        /**
         * Convert an LRRSearchResult into a paginated list of GalleryInfo objects.
         *
         * @param result the raw search result from LANraragi
         * @param page   the current page number (0-based)
         * @return paginated result with gallery info list and page metadata
         */
        @JvmStatic
        fun convertLRRSearchResult(result: LRRSearchResult, page: Int): LRRPaginatedResult {
            val galleryInfoList = mutableListOf<GalleryInfo>()
            result.data?.forEach { archive ->
                galleryInfoList.add(archive.toGalleryInfo())
            }

            val pageSize = galleryInfoList.size
            val totalRecords = result.recordsFiltered
            val totalPages: Int
            val nextPage: Int
            if (pageSize > 0) {
                totalPages = ceil(totalRecords.toDouble() / pageSize).toInt()
                nextPage = if (page + 1 < totalPages) page + 1 else 0
            } else {
                totalPages = 1
                nextPage = 0
            }

            return LRRPaginatedResult(galleryInfoList, totalPages, nextPage)
        }
    }
}
