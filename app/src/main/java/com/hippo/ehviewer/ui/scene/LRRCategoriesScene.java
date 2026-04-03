package com.hippo.ehviewer.ui.scene;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRApiUtilsKt;
import com.hippo.ehviewer.client.lrr.LRRCategoryApi;
import com.hippo.ehviewer.client.lrr.data.LRRCategory;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.scene.Announcer;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * Scene that displays LANraragi categories with full CRUD support.
 * Pinned categories appear first. Clicking a category navigates
 * to GalleryListScene filtered by that category.
 */
public class LRRCategoriesScene extends BaseScene {

    private static final String TAG = "LRRCategoriesScene";

    private RecyclerView mRecyclerView;
    private View mProgress;
    private View mErrorView;
    private ImageView mErrorIcon;
    private TextView mErrorTitle;
    private TextView mErrorMessage;
    private Button mErrorRetry;
    private MaterialToolbar mToolbar;

    private List<LRRCategory> mCategories = new ArrayList<>();
    private CategoryAdapter mAdapter;

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_favourite;
    }

    @Nullable
    @Override
    public View onCreateView2(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_lrr_categories, container, false);

        mToolbar = view.findViewById(R.id.toolbar);
        mProgress = view.findViewById(R.id.progress);
        mErrorView = view.findViewById(R.id.error_view);
        mErrorIcon = view.findViewById(R.id.error_icon);
        mErrorTitle = view.findViewById(R.id.error_title);
        mErrorMessage = view.findViewById(R.id.error_message);
        mErrorRetry = view.findViewById(R.id.error_retry);
        mRecyclerView = view.findViewById(R.id.recycler_view);

        mToolbar.setTitle(R.string.lrr_categories_title);
        mToolbar.setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
        mToolbar.setNavigationOnClickListener(v -> onBackPressed());

        // Add "+" menu item
        mToolbar.inflateMenu(R.menu.scene_lrr_categories);
        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_add_category) {
                showCategoryDialog(null);
                return true;
            }
            return false;
        });

        mAdapter = new CategoryAdapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setAdapter(mAdapter);

        fetchCategories();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecyclerView = null;
        mProgress = null;
        mErrorView = null;
        mErrorIcon = null;
        mErrorTitle = null;
        mErrorMessage = null;
        mErrorRetry = null;
        mToolbar = null;
        mAdapter = null;
    }

    // ==================== Data Loading ====================

    private void fetchCategories() {
        showProgress();

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                Context ctx = getEHContext();
                if (ctx == null) return;
                String serverUrl = LRRAuthManager.getServerUrl();
                OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                @SuppressWarnings("unchecked")
                List<LRRCategory> categories = (List<LRRCategory>) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRCategoryApi.getCategories(client, serverUrl, cont)
                );

                // Sort: pinned first, then by name
                List<LRRCategory> pinned = new ArrayList<>();
                List<LRRCategory> unpinned = new ArrayList<>();
                for (LRRCategory cat : categories) {
                    if (cat.name == null || cat.name.isEmpty()) continue;
                    if (cat.isPinned()) {
                        pinned.add(cat);
                    } else {
                        unpinned.add(cat);
                    }
                }
                pinned.addAll(unpinned);

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    mCategories.clear();
                    mCategories.addAll(pinned);
                    if (mCategories.isEmpty()) {
                        showEmpty(getString(R.string.lrr_categories_empty));
                    } else {
                        showList();
                        if (mAdapter != null) mAdapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load categories", e);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        showError(LRRApiUtilsKt.friendlyError(e)));
            }
        });
    }

    // ==================== CRUD Operations ====================

    /**
     * Show create/edit category dialog.
     * @param category null for create, non-null for edit.
     */
    private void showCategoryDialog(@Nullable LRRCategory category) {
        if (getEHContext() == null) return;
        boolean isEdit = category != null;

        View dialogView = LayoutInflater.from(getEHContext()).inflate(R.layout.dialog_category_edit, null);
        EditText nameInput = dialogView.findViewById(R.id.category_name);
        RadioGroup typeGroup = dialogView.findViewById(R.id.category_type);
        View searchLayout = dialogView.findViewById(R.id.search_layout);
        EditText searchInput = dialogView.findViewById(R.id.category_search);
        SwitchMaterial pinnedSwitch = dialogView.findViewById(R.id.category_pinned);

        // Pre-fill for edit mode
        if (isEdit) {
            nameInput.setText(category.name);
            pinnedSwitch.setChecked(category.isPinned());
            if (category.isDynamic()) {
                typeGroup.check(R.id.type_dynamic);
                searchLayout.setVisibility(View.VISIBLE);
                searchInput.setText(category.search);
            }
            // Disable type switch for edit (can't change static↔dynamic if archives exist)
            if (category.archives != null && !category.archives.isEmpty()) {
                dialogView.findViewById(R.id.type_static).setEnabled(false);
                dialogView.findViewById(R.id.type_dynamic).setEnabled(false);
            }
        }

        // Toggle search field visibility
        typeGroup.setOnCheckedChangeListener((group, id) ->
                searchLayout.setVisibility(id == R.id.type_dynamic ? View.VISIBLE : View.GONE));

        new AlertDialog.Builder(getEHContext())
                .setTitle(isEdit ? R.string.lrr_category_edit : R.string.lrr_category_create)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(getEHContext(), R.string.lrr_category_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean isDynamic = typeGroup.getCheckedRadioButtonId() == R.id.type_dynamic;
                    String search = isDynamic ? searchInput.getText().toString().trim() : null;
                    boolean pinned = pinnedSwitch.isChecked();

                    if (isEdit) {
                        updateCategory(category.id, name, search, pinned);
                    } else {
                        createCategory(name, search, pinned);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createCategory(String name, @Nullable String search, boolean pinned) {
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                Context ctx = getEHContext();
                if (ctx == null) return;
                String serverUrl = LRRAuthManager.getServerUrl();
                OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRCategoryApi.createCategory(client, serverUrl, name, search, pinned, cont));
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getEHContext(), R.string.lrr_category_created, Toast.LENGTH_SHORT).show();
                    fetchCategories();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to create category", e);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getEHContext(), LRRApiUtilsKt.friendlyError(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateCategory(String categoryId, String name, @Nullable String search, boolean pinned) {
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                Context ctx = getEHContext();
                if (ctx == null) return;
                String serverUrl = LRRAuthManager.getServerUrl();
                OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRCategoryApi.updateCategory(client, serverUrl, categoryId, name, search, pinned, cont));
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getEHContext(), R.string.lrr_category_updated, Toast.LENGTH_SHORT).show();
                    fetchCategories();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to update category", e);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getEHContext(), LRRApiUtilsKt.friendlyError(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteCategory(LRRCategory category) {
        new AlertDialog.Builder(getEHContext())
                .setTitle(R.string.lrr_category_action_delete)
                .setMessage(getString(R.string.lrr_category_delete_confirm, category.name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                        try {
                            Context ctx = getEHContext();
                            if (ctx == null) return;
                            String serverUrl = LRRAuthManager.getServerUrl();
                            OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
                            LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> LRRCategoryApi.deleteCategory(client, serverUrl, category.id, cont));
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getEHContext(), R.string.lrr_category_deleted, Toast.LENGTH_SHORT).show();
                                fetchCategories();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to delete category", e);
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() ->
                                    Toast.makeText(getEHContext(), LRRApiUtilsKt.friendlyError(e), Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Show long-press action menu for a category item.
     */
    private void showCategoryActions(LRRCategory category) {
        if (getEHContext() == null) return;

        String pinAction = category.isPinned()
                ? getString(R.string.lrr_category_action_unpin)
                : getString(R.string.lrr_category_action_pin);

        String[] items = {
                getString(R.string.lrr_category_action_edit),
                getString(R.string.lrr_category_action_delete),
                pinAction
        };

        new AlertDialog.Builder(getEHContext())
                .setTitle(category.name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: // Edit
                            showCategoryDialog(category);
                            break;
                        case 1: // Delete
                            deleteCategory(category);
                            break;
                        case 2: // Pin/Unpin
                            updateCategory(category.id, category.name,
                                    category.search, !category.isPinned());
                            break;
                    }
                })
                .show();
    }

    // ==================== View Helpers ====================

    private void showProgress() {
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) mRecyclerView.setVisibility(View.GONE);
        if (mErrorView != null) mErrorView.setVisibility(View.GONE);
    }

    private void showList() {
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
        if (mErrorView != null) mErrorView.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mRecyclerView != null) mRecyclerView.setVisibility(View.GONE);
        if (mErrorView != null) {
            mErrorView.setVisibility(View.VISIBLE);
            if (mErrorIcon != null) mErrorIcon.setVisibility(View.VISIBLE);
            if (mErrorTitle != null) mErrorTitle.setText(R.string.lrr_error_title);
            if (mErrorMessage != null) {
                mErrorMessage.setVisibility(View.VISIBLE);
                mErrorMessage.setText(message);
            }
            if (mErrorRetry != null) {
                mErrorRetry.setVisibility(View.VISIBLE);
                mErrorRetry.setOnClickListener(v -> fetchCategories());
            }
        }
    }

    private void showEmpty(String message) {
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mRecyclerView != null) mRecyclerView.setVisibility(View.GONE);
        if (mErrorView != null) {
            mErrorView.setVisibility(View.VISIBLE);
            if (mErrorIcon != null) mErrorIcon.setVisibility(View.GONE);
            if (mErrorTitle != null) mErrorTitle.setText(message);
            if (mErrorMessage != null) mErrorMessage.setVisibility(View.GONE);
            if (mErrorRetry != null) mErrorRetry.setVisibility(View.GONE);
        }
    }

    private void onCategoryClick(LRRCategory category) {
        Bundle args = new Bundle();
        args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_LIST_URL_BUILDER);

        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);

        if (category.isDynamic()) {
            // Dynamic category: use its search query as keyword
            builder.setKeyword(category.search.trim());
        } else {
            // Static category: use category ID filter
            builder.setKeyword("category:" + category.id);
        }

        args.putParcelable(GalleryListScene.KEY_LIST_URL_BUILDER, builder);
        startScene(new Announcer(GalleryListScene.class).setArgs(args));
    }

    // ==================== Adapter ====================

    private class CategoryAdapter extends RecyclerView.Adapter<CategoryViewHolder> {

        @NonNull
        @Override
        public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_lrr_category, parent, false);
            return new CategoryViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
            LRRCategory cat = mCategories.get(position);

            // Name (with pin indicator)
            if (cat.isPinned()) {
                holder.name.setText(getString(R.string.lrr_category_pinned, cat.name));
            } else {
                holder.name.setText(cat.name);
            }

            // Archive count — dynamic categories show "动态" label
            if (cat.isDynamic()) {
                holder.count.setText(getString(R.string.lrr_category_label_dynamic));
            } else {
                int archiveCount = cat.archives != null ? cat.archives.size() : 0;
                holder.count.setText(getString(R.string.lrr_category_archives, archiveCount));
            }
            holder.search.setVisibility(View.GONE);

            // Uniform icon
            holder.icon.setImageResource(R.drawable.v_heart_primary_x48);

            // Click to browse
            holder.itemView.setOnClickListener(v -> onCategoryClick(cat));

            // Long-press for actions menu
            holder.itemView.setOnLongClickListener(v -> {
                showCategoryActions(cat);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mCategories.size();
        }
    }

    private static class CategoryViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView count;
        final TextView search;

        CategoryViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.category_icon);
            name = itemView.findViewById(R.id.category_name);
            count = itemView.findViewById(R.id.category_count);
            search = itemView.findViewById(R.id.category_search);
        }
    }
}
