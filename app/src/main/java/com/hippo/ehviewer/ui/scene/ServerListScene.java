package com.hippo.ehviewer.ui.scene;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.lrr.LRRApiUtilsKt;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRUrlHelper;
import com.hippo.ehviewer.client.lrr.data.LRRServerInfo;
import com.hippo.ehviewer.dao.ServerProfile;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.scene.Announcer;
import com.hippo.util.IoThreadPoolExecutor;

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

        com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            java.util.List<com.hippo.ehviewer.dao.ServerProfile> result =
                new ArrayList<>(EhDB.getAllServerProfiles());
            result.sort((a, b) -> Boolean.compare(b.isActive(), a.isActive()));
            requireActivity().runOnUiThread(() -> {
                mProfiles = result;
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                if (mEmptyText != null) {
                    mEmptyText.setVisibility(mProfiles.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    private void switchToProfile(ServerProfile profile) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        // DB writes on IO thread, then UI updates on main thread
        com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            EhDB.deactivateAllProfiles();
            EhDB.updateServerProfile(new ServerProfile(
                    profile.getId(), profile.getName(), profile.getUrl(),
                    null, true));
            android.app.Activity a = getActivity();
            if (a != null) a.runOnUiThread(() -> switchToProfileUiUpdate(profile));
        });
    }

    private void switchToProfileUiUpdate(ServerProfile profile) {
        Context ctx = getEHContext();
        if (ctx == null) return;

        // Update LRRAuthManager
        LRRAuthManager.setServerUrl(profile.getUrl());
        String profileApiKey = LRRAuthManager.getApiKeyForProfile(profile.getId());
        LRRAuthManager.setApiKey(profileApiKey);
        LRRAuthManager.setServerName(profile.getName());
        LRRAuthManager.setActiveProfileId(profile.getId());

        // Clear caches so previous server's content doesn't appear under new server
        ServiceRegistry.INSTANCE.clearAllCaches();

        // Reload download manager on IO thread (EhDB.getAllDownloadInfo blocks)
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().reload();
        });

        Toast.makeText(ctx, getString(R.string.lrr_server_switched, profile.getName()),
                Toast.LENGTH_SHORT).show();

        // Warn if switching to HTTP on a public address
        String url = profile.getUrl();
        if (url != null && url.toLowerCase().startsWith("http://")
                && !LRRUrlHelper.isLanAddress(url)) {
            Toast.makeText(ctx, R.string.lrr_security_warning, Toast.LENGTH_LONG).show();
        }

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
                    LRRAuthManager.clearApiKeyForProfile(profile.getId());
                    IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                        EhDB.deleteServerProfile(profile));
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
        String existingKey = LRRAuthManager.getApiKeyForProfile(profile.getId());
        if (existingKey != null) {
            apiKeyEdit.setText(existingKey);
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.lrr_edit_server)
                .setView(dialogView)
                .setPositiveButton(R.string.lrr_edit_server_save, null) // set below to prevent auto-dismiss
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Prevent the dialog's keyboard from resizing the underlying Activity layout.
        // Save the original mode so we can restore it when the dialog is dismissed.
        final int originalSoftInputMode;
        if (getActivity() != null && getActivity().getWindow() != null) {
            originalSoftInputMode = getActivity().getWindow().getAttributes().softInputMode;
            getActivity().getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        } else {
            originalSoftInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        }

        dialog.setOnDismissListener(d -> {
            // Restore the Activity's original soft input mode
            if (getActivity() != null && getActivity().getWindow() != null) {
                getActivity().getWindow().setSoftInputMode(originalSoftInputMode);
            }
        });

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

            // Normalize URL: add protocol if missing, strip trailing slashes
            String normalizedUrl = LRRUrlHelper.normalizeUrl(newUrl);
            if (!LRRUrlHelper.hasExplicitScheme(normalizedUrl)) {
                // Default to https:// for bare hostnames
                normalizedUrl = "https://" + normalizedUrl;
            }

            // Save to DB on IO thread
            final ServerProfile updated = new ServerProfile(
                    profile.getId(), newName, normalizedUrl,
                    null, profile.isActive());
            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                EhDB.updateServerProfile(updated);
                LRRAuthManager.setApiKeyForProfile(profile.getId(), newKey.isEmpty() ? null : newKey);
                if (profile.isActive()) {
                    LRRAuthManager.setServerUrl(updated.getUrl());
                    LRRAuthManager.setApiKey(newKey.isEmpty() ? null : newKey);
                    LRRAuthManager.setServerName(newName);
                }
            });

            // Refresh list immediately with in-memory data
            mProfiles.set(position, updated);
            mAdapter.notifyItemChanged(position);
            dialog.dismiss();
        });
    }

    /**
     * Show a dialog to add a new server profile.
     * Reuses dialog_edit_server.xml layout. Tests connection with HTTPS→HTTP
     * fallback, then saves and switches to the new server.
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

            // Normalize: trim + strip trailing slashes
            final String normalizedInput = LRRUrlHelper.normalizeUrl(url);
            final String finalKey = apiKey.isEmpty() ? null : apiKey;

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show();

            // Temporarily set auth for the interceptor
            String oldUrl = LRRAuthManager.getServerUrl();
            String oldKey = LRRAuthManager.getApiKey();
            LRRAuthManager.setApiKey(finalKey);

            // Build a test client with short timeouts
            OkHttpClient baseClient = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
            OkHttpClient testClient = LRRUrlHelper.buildTestClient(baseClient);

            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                // Use HTTPS→HTTP fallback logic
                LRRUrlHelper.connectWithFallback(testClient, normalizedInput,
                        new LRRUrlHelper.ConnectCallback() {
                    @Override
                    public void onSuccess(@NonNull String resolvedUrl,
                                          @NonNull LRRServerInfo info,
                                          boolean usedHttpFallback) {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            // Set auth immediately (in-memory via .apply())
                            LRRAuthManager.setServerUrl(resolvedUrl);
                            LRRAuthManager.setApiKey(finalKey);
                            LRRAuthManager.setServerName(name);

                            // DB operations on IO thread
                            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                                EhDB.deactivateAllProfiles();
                                ServerProfile newProfile = new ServerProfile(
                                        0, name, resolvedUrl, null, true);
                                long newId = EhDB.insertServerProfile(newProfile);
                                LRRAuthManager.setApiKeyForProfile(newId, finalKey);
                                LRRAuthManager.setActiveProfileId(newId);
                                ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().reload();
                            });

                            Toast.makeText(ctx, getString(R.string.lrr_connection_success,
                                    info.name, info.version), Toast.LENGTH_SHORT).show();

                            // Warn if connected via HTTP on a public address
                            if (usedHttpFallback && !LRRUrlHelper.isLanAddress(resolvedUrl)) {
                                Toast.makeText(ctx, R.string.lrr_security_warning,
                                        Toast.LENGTH_LONG).show();
                            }

                            dialog.dismiss();
                            loadProfiles();

                            // Navigate to archive list
                            Bundle args = new Bundle();
                            args.putString(GalleryListScene.KEY_ACTION,
                                    GalleryListScene.ACTION_HOMEPAGE);
                            if (getActivity() instanceof com.hippo.scene.StageActivity) {
                                ((com.hippo.scene.StageActivity) getActivity())
                                        .startSceneFirstly(new Announcer(GalleryListScene.class)
                                                .setArgs(args));
                            }
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Exception error) {
                        // Restore old auth on failure
                        LRRAuthManager.setServerUrl(oldUrl);
                        LRRAuthManager.setApiKey(oldKey);

                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            Toast.makeText(ctx, getString(R.string.lrr_connection_failed,
                                    LRRApiUtilsKt.friendlyError(ctx, error)),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
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
