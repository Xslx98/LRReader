package com.hippo.ehviewer.ui.scene;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.lrr.LRRApiUtilsKt;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRServerApi;
import com.hippo.ehviewer.client.lrr.data.LRRServerInfo;
import com.hippo.ehviewer.dao.ServerProfile;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.scene.Announcer;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.concurrent.TimeUnit;

import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Server list management scene. Shows all saved server profiles in a list.
 * Tap to switch, long-press for edit/delete, FAB to add new server.
 */
public final class ServerListScene extends BaseScene {

    private RecyclerView mRecyclerView;
    private TextView mEmptyText;
    private ServerAdapter mAdapter;
    private List<ServerProfile> mProfiles = new ArrayList<>();

    @Override
    public boolean needShowLeftDrawer() {
        return true;
    }

    @Nullable
    @Override
    public View onCreateView2(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_server_list, container, false);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        mRecyclerView = view.findViewById(R.id.recycler_view);
        mEmptyText = view.findViewById(R.id.empty_text);
        FloatingActionButton fab = view.findViewById(R.id.fab_add);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getEHContext()));
        mAdapter = new ServerAdapter();
        mRecyclerView.setAdapter(mAdapter);

        fab.setOnClickListener(v -> showAddDialog());

        loadProfiles();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh list when returning from ServerConfigScene
        loadProfiles();
    }

    private void loadProfiles() {
        Context ctx = getEHContext();
        if (ctx == null) return;

        mProfiles = new ArrayList<>(EhDB.getAllServerProfiles());
        // Sort: active server first
        mProfiles.sort((a, b) -> Boolean.compare(b.isActive(), a.isActive()));
        if (mAdapter != null) mAdapter.notifyDataSetChanged();

        if (mEmptyText != null) {
            mEmptyText.setVisibility(mProfiles.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void switchToProfile(ServerProfile profile) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        // Deactivate all, activate selected
        EhDB.deactivateAllProfiles();
        EhDB.updateServerProfile(new ServerProfile(
                profile.getId(), profile.getName(), profile.getUrl(),
                profile.getApiKey(), true));

        // Update LRRAuthManager
        LRRAuthManager.setServerUrl(profile.getUrl());
        LRRAuthManager.setApiKey(profile.getApiKey());
        LRRAuthManager.setServerName(profile.getName());
        LRRAuthManager.setActiveProfileId(profile.getId());

        // Reload download manager
        EhApplication.getDownloadManager(ctx).reload();

        Toast.makeText(ctx, getString(R.string.lrr_server_switched, profile.getName()),
                Toast.LENGTH_SHORT).show();

        // Navigate to archive list
        Bundle args = new Bundle();
        args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE);
        if (getActivity() instanceof com.hippo.scene.StageActivity) {
            ((com.hippo.scene.StageActivity) getActivity())
                    .startSceneFirstly(new Announcer(GalleryListScene.class).setArgs(args));
        }
    }

    private void deleteProfile(ServerProfile profile, int position) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.lrr_delete_server)
                .setMessage(getString(R.string.lrr_delete_server_confirm, profile.getName()))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    EhDB.deleteServerProfile(profile);
                    mProfiles.remove(position);
                    mAdapter.notifyItemRemoved(position);
                    if (mEmptyText != null) {
                        mEmptyText.setVisibility(mProfiles.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ===== Adapter =====

    private class ServerAdapter extends RecyclerView.Adapter<ServerViewHolder> {

        @NonNull
        @Override
        public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_server_profile, parent, false);
            return new ServerViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
            ServerProfile profile = mProfiles.get(position);
            holder.name.setText(profile.getName());
            holder.url.setText(profile.getUrl());

            // Highlight the currently active/connected server
            if (profile.isActive()) {
                holder.activeIcon.setVisibility(View.VISIBLE);
                holder.name.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.colorPrimary));
                holder.itemView.setBackgroundColor(Color.parseColor("#1A4CAF50")); // subtle green tint
            } else {
                holder.activeIcon.setVisibility(View.INVISIBLE);
                holder.name.setTextColor(Color.parseColor("#999999"));
                holder.url.setTextColor(Color.parseColor("#BBBBBB"));
                holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            }

            holder.itemView.setOnClickListener(v -> switchToProfile(profile));
            holder.itemView.setOnLongClickListener(v -> {
                showProfileOptions(profile, position);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mProfiles.size();
        }
    }

    private static class ServerViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView url;
        final ImageView activeIcon;

        ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.server_name);
            url = itemView.findViewById(R.id.server_url);
            activeIcon = itemView.findViewById(R.id.icon_active);
        }
    }

    private void showProfileOptions(ServerProfile profile, int position) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        String[] options = {
                getString(R.string.lrr_edit_server),
                getString(R.string.lrr_delete_server)
        };

        new AlertDialog.Builder(ctx)
                .setTitle(profile.getName())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showEditDialog(profile, position);
                    } else if (which == 1) {
                        deleteProfile(profile, position);
                    }
                })
                .show();
    }

    private void showEditDialog(ServerProfile profile, int position) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null);
        com.google.android.material.textfield.TextInputEditText nameEdit =
                dialogView.findViewById(R.id.edit_server_name);
        com.google.android.material.textfield.TextInputEditText urlEdit =
                dialogView.findViewById(R.id.edit_server_url);
        com.google.android.material.textfield.TextInputEditText apiKeyEdit =
                dialogView.findViewById(R.id.edit_api_key);

        // Pre-fill current values
        nameEdit.setText(profile.getName());
        urlEdit.setText(profile.getUrl());
        if (profile.getApiKey() != null) {
            apiKeyEdit.setText(profile.getApiKey());
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.lrr_edit_server)
                .setView(dialogView)
                .setPositiveButton(R.string.lrr_edit_server_save, null) // set below to prevent auto-dismiss
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.show();

        // Override positive button to add validation
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = nameEdit.getText() != null ? nameEdit.getText().toString().trim() : "";
            String newUrl = urlEdit.getText() != null ? urlEdit.getText().toString().trim() : "";
            String newKey = apiKeyEdit.getText() != null ? apiKeyEdit.getText().toString().trim() : "";

            if (newName.isEmpty()) {
                nameEdit.setError(getString(R.string.name_is_empty));
                return;
            }
            if (newUrl.isEmpty()) {
                urlEdit.setError(getString(R.string.lrr_server_url_empty));
                return;
            }

            // Save to DB
            ServerProfile updated = new ServerProfile(
                    profile.getId(), newName, newUrl,
                    newKey.isEmpty() ? null : newKey,
                    profile.isActive());
            EhDB.updateServerProfile(updated);

            // If this is the active profile, update LRRAuthManager too
            if (profile.isActive()) {
                LRRAuthManager.setServerUrl(newUrl);
                LRRAuthManager.setApiKey(newKey.isEmpty() ? null : newKey);
                LRRAuthManager.setServerName(newName);
            }

            // Refresh list
            mProfiles.set(position, updated);
            mAdapter.notifyItemChanged(position);

            dialog.dismiss();
        });
    }

    /**
     * Show a dialog to add a new server profile.
     * Reuses dialog_edit_server.xml layout. Tests connection, then saves and optionally switches.
     */
    private void showAddDialog() {
        Context ctx = getEHContext();
        if (ctx == null) return;

        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null);
        com.google.android.material.textfield.TextInputEditText nameEdit =
                dialogView.findViewById(R.id.edit_server_name);
        com.google.android.material.textfield.TextInputEditText urlEdit =
                dialogView.findViewById(R.id.edit_server_url);
        com.google.android.material.textfield.TextInputEditText apiKeyEdit =
                dialogView.findViewById(R.id.edit_api_key);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.lrr_add_server)
                .setView(dialogView)
                .setPositiveButton(R.string.lrr_save_and_connect, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameEdit.getText() != null ? nameEdit.getText().toString().trim() : "";
            String url = urlEdit.getText() != null ? urlEdit.getText().toString().trim() : "";
            String apiKey = apiKeyEdit.getText() != null ? apiKeyEdit.getText().toString().trim() : "";

            if (name.isEmpty()) {
                nameEdit.setError(getString(R.string.name_is_empty));
                return;
            }
            if (url.isEmpty()) {
                urlEdit.setError(getString(R.string.lrr_server_url_empty));
                return;
            }

            // Normalize URL
            String normalizedUrl = url;
            if (!normalizedUrl.toLowerCase().startsWith("http://") &&
                    !normalizedUrl.toLowerCase().startsWith("https://")) {
                normalizedUrl = "http://" + normalizedUrl;
            }
            while (normalizedUrl.endsWith("/")) {
                normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
            }

            final String finalUrl = normalizedUrl;
            final String finalKey = apiKey.isEmpty() ? null : apiKey;

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show();

            // Test connection before saving
            OkHttpClient baseClient = EhApplication.getOkHttpClient(ctx);
            OkHttpClient testClient = baseClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            // Temporarily set auth for test
            String oldUrl = LRRAuthManager.getServerUrl();
            String oldKey = LRRAuthManager.getApiKey();
            LRRAuthManager.setServerUrl(finalUrl);
            LRRAuthManager.setApiKey(finalKey);

            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                try {
                    LRRServerInfo info = (LRRServerInfo) BuildersKt.runBlocking(
                            EmptyCoroutineContext.INSTANCE,
                            (scope, cont) -> LRRServerApi.getServerInfo(testClient, finalUrl, cont)
                    );

                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        // Connection success — save as new profile and switch to it
                        EhDB.deactivateAllProfiles();
                        String profileName = name;
                        ServerProfile newProfile = new ServerProfile(
                                0, profileName, finalUrl, finalKey, true);
                        long newId = EhDB.insertServerProfile(newProfile);

                        LRRAuthManager.setServerUrl(finalUrl);
                        LRRAuthManager.setApiKey(finalKey);
                        LRRAuthManager.setServerName(profileName);
                        LRRAuthManager.setActiveProfileId(newId);

                        EhApplication.getDownloadManager(ctx).reload();

                        Toast.makeText(ctx, getString(R.string.lrr_connection_success,
                                info.name, info.version), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadProfiles();

                        // Navigate to archive list
                        Bundle args = new Bundle();
                        args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE);
                        if (getActivity() instanceof com.hippo.scene.StageActivity) {
                            ((com.hippo.scene.StageActivity) getActivity())
                                    .startSceneFirstly(new Announcer(GalleryListScene.class).setArgs(args));
                        }
                    });
                } catch (Exception e) {
                    // Restore old auth on failure
                    LRRAuthManager.setServerUrl(oldUrl);
                    LRRAuthManager.setApiKey(oldKey);

                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        Toast.makeText(ctx, getString(R.string.lrr_connection_failed,
                                LRRApiUtilsKt.friendlyError(e)), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecyclerView = null;
        mEmptyText = null;
        mAdapter = null;
    }
}
