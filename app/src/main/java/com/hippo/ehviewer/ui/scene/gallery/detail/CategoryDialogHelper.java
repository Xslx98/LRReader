package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRCategoryApi;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.ehviewer.client.lrr.LRRApiUtilsKt;
import com.hippo.ehviewer.client.lrr.data.LRRCategory;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * Encapsulates the LANraragi category selection dialog logic.
 * Extracted from GalleryDetailScene.onClick(mHeartGroup).
 */
public class CategoryDialogHelper {

    /**
     * Callback for when the favorite status changes after category operations.
     */
    public interface Callback {
        void onFavoriteStatusChanged(boolean isFavorited, String favoriteName);
    }

    /**
     * Show the category selection dialog for a given archive.
     * Loads categories from the server, presents a checkbox list,
     * and applies changes on confirmation.
     */
    public static void showCategoryDialog(Activity activity, GalleryDetail gd, Callback callback) {
        if (activity == null || gd == null) return;

        final String arcid = gd.token;
        final String serverUrl = LRRAuthManager.getServerUrl();
        if (arcid == null || serverUrl == null) return;

        Toast.makeText(activity, R.string.lrr_loading_categories, Toast.LENGTH_SHORT).show();

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                @SuppressWarnings("unchecked")
                List<LRRCategory> categories =
                        (List<LRRCategory>) kotlinx.coroutines.BuildersKt.runBlocking(
                                kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                                (scope, cont) -> LRRCategoryApi.getCategories(client, serverUrl, cont)
                        );

                // Filter to static categories only
                List<LRRCategory> staticCats = new ArrayList<>();
                for (LRRCategory cat : categories) {
                    if (cat.search == null || cat.search.isEmpty()) {
                        staticCats.add(cat);
                    }
                }

                if (staticCats.isEmpty()) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            Toast.makeText(activity, R.string.lrr_no_static_categories, Toast.LENGTH_SHORT).show());
                    return;
                }

                String[] names = new String[staticCats.size()];
                boolean[] checked = new boolean[staticCats.size()];
                for (int i = 0; i < staticCats.size(); i++) {
                    LRRCategory cat = staticCats.get(i);
                    names[i] = cat.name + " (" + (cat.archives != null ? cat.archives.size() : 0) + ")";
                    checked[i] = cat.archives != null && cat.archives.contains(arcid);
                }
                final boolean[] originalChecked = checked.clone();

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        showCategoryCheckboxDialog(activity, staticCats, names, checked, originalChecked,
                                arcid, serverUrl, callback));
            } catch (Exception e) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(activity, LRRApiUtilsKt.friendlyError(activity, e),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void showCategoryCheckboxDialog(
            Activity activity, List<LRRCategory> staticCats,
            String[] names, boolean[] checked, boolean[] originalChecked,
            String arcid, String serverUrl, Callback callback) {

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, pad / 2);

        for (int i = 0; i < staticCats.size(); i++) {
            CheckBox cb = new CheckBox(activity);
            cb.setText(names[i]);
            cb.setChecked(checked[i]);
            final int idx = i;
            cb.setOnCheckedChangeListener((btn, isChecked) -> checked[idx] = isChecked);
            container.addView(cb);
        }

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(container);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Math.min(FrameLayout.LayoutParams.WRAP_CONTENT, (int) (screenH * 0.6))));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.lrr_add_to_category)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        applyCategoryChanges(activity, staticCats, checked, originalChecked,
                                arcid, serverUrl, callback))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.lrr_category_create_inline, (dialog, which) ->
                        showCreateCategoryDialog(activity, arcid, serverUrl, callback))
                .show();
    }

    private static void applyCategoryChanges(
            Activity activity, List<LRRCategory> staticCats,
            boolean[] checked, boolean[] originalChecked,
            String arcid, String serverUrl, Callback callback) {

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                OkHttpClient c = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                for (int i = 0; i < staticCats.size(); i++) {
                    if (checked[i] != originalChecked[i]) {
                        String catId = staticCats.get(i).id;
                        if (checked[i]) {
                            LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> LRRCategoryApi.addToCategory(c, serverUrl, catId, arcid, cont));
                        } else {
                            LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> LRRCategoryApi.removeFromCategory(c, serverUrl, catId, arcid, cont));
                        }
                    }
                }

                List<String> newFavNames = new ArrayList<>();
                for (int i = 0; i < staticCats.size(); i++) {
                    if (checked[i]) {
                        newFavNames.add(staticCats.get(i).name);
                    }
                }

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (callback != null) {
                        boolean isFav = !newFavNames.isEmpty();
                        String favName;
                        if (newFavNames.isEmpty()) {
                            favName = null;
                        } else if (newFavNames.size() == 1) {
                            favName = newFavNames.get(0);
                        } else {
                            favName = newFavNames.get(0)
                                    + activity.getString(R.string.lrr_category_info_suffix)
                                    + newFavNames.size()
                                    + activity.getString(R.string.lrr_category_count_suffix);
                        }
                        callback.onFavoriteStatusChanged(isFav, favName);
                    }
                    Toast.makeText(activity, R.string.lrr_category_updated_toast, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(activity, LRRApiUtilsKt.friendlyError(activity, e),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void showCreateCategoryDialog(
            Activity activity, String arcid, String serverUrl, Callback callback) {

        EditText input = new EditText(activity);
        input.setHint(R.string.lrr_category_name_hint);
        input.setSingleLine(true);
        int px = (int) (24 * activity.getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(activity);
        frame.setPadding(px, px / 2, px, 0);
        frame.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.lrr_category_create)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String catName = input.getText().toString().trim();
                    if (catName.isEmpty()) {
                        Toast.makeText(activity, R.string.lrr_category_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                        try {
                            OkHttpClient c = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                            String newCatId = (String) LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> LRRCategoryApi.createCategory(c, serverUrl, catName, null, false, cont));
                            LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> LRRCategoryApi.addToCategory(c, serverUrl, newCatId, arcid, cont));

                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                Toast.makeText(activity, R.string.lrr_category_created, Toast.LENGTH_SHORT).show();
                                if (callback != null) {
                                    callback.onFavoriteStatusChanged(true, catName);
                                }
                            });
                        } catch (Exception ex) {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                    Toast.makeText(activity, LRRApiUtilsKt.friendlyError(activity, ex),
                                            Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
