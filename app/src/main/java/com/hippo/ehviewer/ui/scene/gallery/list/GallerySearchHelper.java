package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.lrr.data.LRRArchive;
import com.hippo.ehviewer.client.lrr.data.LRRSearchResult;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser;
import com.hippo.ehviewer.settings.AppearanceSettings;
import com.hippo.ehviewer.ui.scene.ProgressScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.scene.Announcer;
import com.hippo.util.DrawableManager;
import com.hippo.lib.yorozuya.MathUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles search-related utility logic for GalleryListScene.
 * Contains URL suggestion classes, search bar configuration helpers,
 * keyword processing, title computation, and LRR search result conversion.
 */
public class GallerySearchHelper {

    /**
     * Callback for operations that require scene interaction
     * (navigation, state management, context access).
     */
    public interface Callback {
        @Nullable Context getHostContext();
        @Nullable Resources getHostResources();
        void navigateToScene(Announcer announcer);
        int getSearchState();
        void setSearchState(int state);
    }

    private final Callback mCallback;

    public GallerySearchHelper(Callback callback) {
        mCallback = callback;
    }

    // -----------------------------------------------------------------------
    // Static utility methods (no callback needed)
    // -----------------------------------------------------------------------

    /**
     * Compute a human-readable title for the given ListUrlBuilder.
     * Returns null if no suitable title can be determined.
     */
    @Nullable
    public static String getSuitableTitleForUrlBuilder(
            Resources resources, ListUrlBuilder urlBuilder, boolean appName) {
        String keyword = urlBuilder.getKeyword();
        int category = urlBuilder.getCategory();

        if (ListUrlBuilder.MODE_NORMAL == urlBuilder.getMode() &&
                EhUtils.NONE == category &&
                TextUtils.isEmpty(keyword) &&
                urlBuilder.getAdvanceSearch() == -1 &&
                urlBuilder.getMinRating() == -1 &&
                urlBuilder.getPageFrom() == -1 &&
                urlBuilder.getPageTo() == -1) {
            return resources.getString(appName ? R.string.app_name : R.string.homepage);
        } else if (ListUrlBuilder.MODE_SUBSCRIPTION == urlBuilder.getMode() &&
                EhUtils.NONE == category &&
                TextUtils.isEmpty(keyword) &&
                urlBuilder.getAdvanceSearch() == -1 &&
                urlBuilder.getMinRating() == -1 &&
                urlBuilder.getPageFrom() == -1 &&
                urlBuilder.getPageTo() == -1) {
            return resources.getString(R.string.subscription);
        } else if (ListUrlBuilder.MODE_WHATS_HOT == urlBuilder.getMode()) {
            return resources.getString(R.string.whats_hot);
        } else if (!TextUtils.isEmpty(keyword)) {
            return keyword;
        } else if (MathUtils.hammingWeight(category) == 1) {
            return EhUtils.getCategory(category);
        } else {
            return null;
        }
    }

    /**
     * Wrap a tag keyword with quotes if it contains a namespace and spaces.
     * E.g. "artist:some name" becomes "artist:\"some name$\"".
     */
    @NonNull
    public static String wrapTagKeyword(@NonNull String keyword) {
        keyword = keyword.trim();

        int index1 = keyword.indexOf(':');
        if (index1 == -1 || index1 >= keyword.length() - 1) {
            // Can't find :, or : is the last char
            return keyword;
        }
        if (keyword.charAt(index1 + 1) == '"') {
            // The char after : is ", the word must be quoted
            return keyword;
        }
        int index2 = keyword.indexOf(' ');
        if (index2 <= index1) {
            // Can't find space, or space is before :
            return keyword;
        }

        return keyword.substring(0, index1 + 1) + "\"" + keyword.substring(index1 + 1) + "$\"";
    }

    /**
     * Configure the search bar hint with the magnify icon and site-specific text.
     */
    public static void setSearchBarHint(@NonNull Context context, @NonNull SearchBar searchBar) {
        Resources resources = context.getResources();
        Drawable searchImage = DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24);
        SpannableStringBuilder ssb = new SpannableStringBuilder("   ");
        ssb.append(resources.getString(EhUrl.SITE_EX == AppearanceSettings.getGallerySite() ?
                R.string.gallery_list_search_bar_hint_exhentai :
                R.string.gallery_list_search_bar_hint_e_hentai));
        int textSize = (int) (searchBar.getEditTextTextSize() * 1.25);
        if (searchImage != null) {
            searchImage.setBounds(0, 0, textSize, textSize);
            ssb.setSpan(new ImageSpan(searchImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        searchBar.setEditTextHint(ssb);
    }

    /**
     * Result holder for LRR search result conversion.
     */
    public static class LRRPaginatedResult {
        public final List<GalleryInfo> galleryInfoList;
        public final int totalPages;
        public final int nextPage;

        public LRRPaginatedResult(List<GalleryInfo> galleryInfoList, int totalPages, int nextPage) {
            this.galleryInfoList = galleryInfoList;
            this.totalPages = totalPages;
            this.nextPage = nextPage;
        }
    }

    /**
     * Convert an LRRSearchResult into a paginated list of GalleryInfo objects.
     *
     * @param result the raw search result from LANraragi
     * @param page   the current page number (0-based)
     * @return paginated result with gallery info list and page metadata
     */
    @NonNull
    public static LRRPaginatedResult convertLRRSearchResult(
            @NonNull LRRSearchResult result, int page) {
        List<GalleryInfo> galleryInfoList = new ArrayList<>();
        if (result.data != null) {
            for (LRRArchive archive : result.data) {
                galleryInfoList.add(archive.toGalleryInfo());
            }
        }

        int pageSize = galleryInfoList.size();
        int totalRecords = result.recordsFiltered;
        int totalPages;
        int nextPage;
        if (pageSize > 0) {
            totalPages = (int) Math.ceil((double) totalRecords / pageSize);
            nextPage = page + 1 < totalPages ? page + 1 : 0;
        } else {
            totalPages = 1;
            nextPage = 0;
        }

        return new LRRPaginatedResult(galleryInfoList, totalPages, nextPage);
    }

    // -----------------------------------------------------------------------
    // Instance methods (use callback for scene interaction)
    // -----------------------------------------------------------------------

    /**
     * Create a SuggestionProvider that parses gallery/page URLs from search text
     * and returns navigation suggestions.
     */
    @NonNull
    public SearchBar.SuggestionProvider createSuggestionProvider() {
        return text -> {
            GalleryDetailUrlParser.Result result1 = GalleryDetailUrlParser.parse(text, false);
            if (result1 != null) {
                return Collections.singletonList(
                        new GalleryDetailUrlSuggestion(mCallback, result1.gid, result1.token));
            }
            GalleryPageUrlParser.Result result2 = GalleryPageUrlParser.parse(text, false);
            if (result2 != null) {
                return Collections.singletonList(
                        new GalleryPageUrlSuggestion(mCallback, result2.gid, result2.pToken, result2.page));
            }
            return null;
        };
    }

    // -----------------------------------------------------------------------
    // Suggestion inner classes
    // -----------------------------------------------------------------------

    /**
     * Base suggestion class for URL-based gallery navigation.
     */
    static abstract class UrlSuggestion extends SearchBar.Suggestion {
        private final Callback mCallback;

        UrlSuggestion(Callback callback) {
            mCallback = callback;
        }

        @Override
        public CharSequence getText(float textSize) {
            Context context = mCallback.getHostContext();
            Resources resources = mCallback.getHostResources();
            if (context == null || resources == null) {
                return null;
            }
            Drawable bookImage = DrawableManager.getVectorDrawable(context, R.drawable.v_book_open_x24);
            SpannableStringBuilder ssb = new SpannableStringBuilder("    ");
            ssb.append(resources.getString(R.string.gallery_list_search_bar_open_gallery));
            int imageSize = (int) (textSize * 1.25);
            if (bookImage != null) {
                bookImage.setBounds(0, 0, imageSize, imageSize);
                ssb.setSpan(new ImageSpan(bookImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return ssb;
        }

        @Override
        public void onClick() {
            mCallback.navigateToScene(createAnnouncer());

            int state = mCallback.getSearchState();
            if (state == GalleryListScene.STATE_SIMPLE_SEARCH) {
                mCallback.setSearchState(GalleryListScene.STATE_NORMAL);
            } else if (state == GalleryListScene.STATE_SEARCH_SHOW_LIST) {
                mCallback.setSearchState(GalleryListScene.STATE_SEARCH);
            }
        }

        abstract Announcer createAnnouncer();

        @Override
        public void onLongClick() {
        }
    }

    /**
     * Suggestion for navigating to a gallery detail page by GID + token.
     */
    static class GalleryDetailUrlSuggestion extends UrlSuggestion {
        private final long mGid;
        private final String mToken;

        GalleryDetailUrlSuggestion(Callback callback, long gid, String token) {
            super(callback);
            mGid = gid;
            mToken = token;
        }

        @Override
        Announcer createAnnouncer() {
            Bundle args = new Bundle();
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN);
            args.putLong(GalleryDetailScene.KEY_GID, mGid);
            args.putString(GalleryDetailScene.KEY_TOKEN, mToken);
            return new Announcer(GalleryDetailScene.class).setArgs(args);
        }

        @Override
        public CharSequence getText(TextView textView) {
            return null;
        }
    }

    /**
     * Suggestion for navigating to a gallery page by GID + page token + page number.
     */
    static class GalleryPageUrlSuggestion extends UrlSuggestion {
        private final long mGid;
        private final String mPToken;
        private final int mPage;

        GalleryPageUrlSuggestion(Callback callback, long gid, String pToken, int page) {
            super(callback);
            mGid = gid;
            mPToken = pToken;
            mPage = page;
        }

        @Override
        Announcer createAnnouncer() {
            Bundle args = new Bundle();
            args.putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN);
            args.putLong(ProgressScene.KEY_GID, mGid);
            args.putString(ProgressScene.KEY_PTOKEN, mPToken);
            args.putInt(ProgressScene.KEY_PAGE, mPage);
            return new Announcer(ProgressScene.class).setArgs(args);
        }

        @Override
        public CharSequence getText(TextView textView) {
            return null;
        }
    }
}
