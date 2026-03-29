package com.hippo.ehviewer.ui.scene;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRApiUtilsKt;
import com.hippo.ehviewer.client.lrr.LRRServerApi;
import com.hippo.ehviewer.client.lrr.LRRUrlHelper;
import com.hippo.ehviewer.client.lrr.data.LRRServerInfo;
import com.hippo.ehviewer.dao.ServerProfile;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.scene.Announcer;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.lib.yorozuya.ViewUtils;

import okhttp3.OkHttpClient;

/**
 * Server configuration scene for LANraragi Reader.
 * Allows users to enter server address and password, test the connection,
 * and proceed to the archive list.
 *
 * URL auto-detection:
 *  - If user types "192.168.1.100:3000", try https:// first, fall back to http://
 *  - If user explicitly types "http://..." or "https://...", use as-is
 *  - On success, update the input field with the resolved URL
 */
public final class ServerConfigScene extends SolidScene implements View.OnClickListener {

    private static final String TAG = "ServerConfigScene";

    @Nullable private View mProgress;
    @Nullable private TextInputLayout mServerUrlLayout;
    @Nullable private TextInputLayout mApiKeyLayout;
    @Nullable private EditText mServerUrl;
    @Nullable private EditText mApiKey;
    @Nullable private LinearLayout mServerInfoPanel;
    @Nullable private TextView mServerInfoText;

    private boolean mConnecting = false;

    @Override
    public boolean needShowLeftDrawer() {
        // Show drawer when server is already configured (allows back navigation)
        return LRRAuthManager.isConfigured();
    }

    @Nullable
    @Override
    public View onCreateView2(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_server_config, container, false);

        View configForm = ViewUtils.$$(view, R.id.config_form);
        mProgress = ViewUtils.$$(view, R.id.progress);
        mServerUrlLayout = (TextInputLayout) ViewUtils.$$(configForm, R.id.server_url_layout);
        mServerUrl = (EditText) ViewUtils.$$(configForm, R.id.server_url);
        mApiKeyLayout = (TextInputLayout) ViewUtils.$$(configForm, R.id.api_key_layout);
        mApiKey = (EditText) ViewUtils.$$(configForm, R.id.api_key);
        mServerInfoPanel = (LinearLayout) ViewUtils.$$(configForm, R.id.server_info_panel);
        mServerInfoText = (TextView) ViewUtils.$$(configForm, R.id.server_info_text);

        View testButton = ViewUtils.$$(configForm, R.id.test_connection);
        View connectButton = ViewUtils.$$(configForm, R.id.connect);

        testButton.setOnClickListener(this);
        connectButton.setOnClickListener(this);

        // Pre-fill existing settings
        String savedUrl = LRRAuthManager.getServerUrl();
        String savedKey = LRRAuthManager.getApiKey();
        if (savedUrl != null && mServerUrl != null) {
            mServerUrl.setText(savedUrl);
        }
        if (savedKey != null && mApiKey != null) {
            mApiKey.setText(savedKey);
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mProgress = null;
        mServerUrlLayout = null;
        mApiKeyLayout = null;
        mServerUrl = null;
        mApiKey = null;
        mServerInfoPanel = null;
        mServerInfoText = null;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.test_connection) {
            doConnectionAttempt(false);
        } else if (v.getId() == R.id.connect) {
            doConnectionAttempt(true);
        }
    }

    /**
     * Get raw user input, trimmed and without trailing slash.
     */
    private String getRawInput() {
        if (mServerUrl == null) return null;
        return LRRUrlHelper.normalizeUrl(mServerUrl.getText().toString());
    }

    private String getApiKey() {
        if (mApiKey == null) return null;
        return mApiKey.getText().toString().trim();
    }

    /**
     * Core connection method. Handles protocol auto-detection:
     *  1. If user typed explicit scheme → use as-is
     *  2. If no scheme → try https:// first, on failure try http://
     *
     * @param navigateOnSuccess if true, navigate to archive list on success (Connect button);
     *                          if false, just show server info (Test button)
     */
    private void doConnectionAttempt(boolean navigateOnSuccess) {
        if (mConnecting) return;

        String rawInput = getRawInput();
        if (TextUtils.isEmpty(rawInput)) {
            if (mServerUrlLayout != null) {
                mServerUrlLayout.setError(getString(R.string.lrr_server_url_empty));
            }
            return;
        }
        if (mServerUrlLayout != null) mServerUrlLayout.setError(null);

        hideSoftInput();
        showProgress(true);
        mConnecting = true;

        // Save API key
        String apiKey = getApiKey();
        if (!TextUtils.isEmpty(apiKey)) {
            LRRAuthManager.setApiKey(apiKey);
        } else {
            LRRAuthManager.setApiKey(null);
        }

        OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();
        OkHttpClient testClient = LRRUrlHelper.buildTestClient(client);

        if (LRRUrlHelper.hasExplicitScheme(rawInput)) {
            // User specified protocol explicitly — use as-is
            LRRAuthManager.setServerUrl(rawInput);
            tryConnect(testClient, rawInput, null, navigateOnSuccess);
        } else {
            // No explicit scheme: try HTTPS first, then fall back to HTTP
            String httpsUrl = "https://" + rawInput;
            String httpUrl = "http://" + rawInput;
            LRRAuthManager.setServerUrl(httpsUrl);  // temp set for interceptor
            tryConnect(testClient, httpsUrl, httpUrl, navigateOnSuccess);
        }
    }

    /**
     * Try connecting to primaryUrl. On failure, if fallbackUrl is non-null, try that too.
     */
    private void tryConnect(OkHttpClient client, String primaryUrl,
                            @Nullable String fallbackUrl, boolean navigateOnSuccess) {
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            // --- Attempt 1: primary URL ---
            try {
                Log.d(TAG, "Trying primary URL: " + primaryUrl);
                LRRServerInfo info = (LRRServerInfo) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRServerApi.getServerInfo(client, primaryUrl, cont)
                );
                // Success on primary
                onConnectSuccess(primaryUrl, info, navigateOnSuccess);
                return;
            } catch (Exception e1) {
                Log.d(TAG, "Primary URL failed: " + e1.getMessage());

                if (fallbackUrl == null) {
                    // No fallback — report failure
                    onConnectFailure(e1);
                    return;
                }
            }

            // --- Attempt 2: fallback URL ---
            try {
                Log.d(TAG, "Trying fallback URL: " + fallbackUrl);
                // Update interceptor URL for fallback attempt
                LRRAuthManager.setServerUrl(fallbackUrl);
                LRRServerInfo info = (LRRServerInfo) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRServerApi.getServerInfo(client, fallbackUrl, cont)
                );
                // Success on fallback
                onConnectSuccess(fallbackUrl, info, navigateOnSuccess);
            } catch (Exception e2) {
                Log.d(TAG, "Fallback URL also failed: " + e2.getMessage());
                onConnectFailure(e2);
            }
        });
    }

    /**
     * Called on worker thread when connection succeeds.
     */
    private void onConnectSuccess(String resolvedUrl, LRRServerInfo info,
                                  boolean navigateOnSuccess) {
        LRRAuthManager.setServerUrl(resolvedUrl);
        LRRAuthManager.setServerName(info.name);

        // Create or update ServerProfile
        if (getActivity() != null) {
            ServerProfile existing = EhDB.findProfileByUrl(resolvedUrl);
            EhDB.deactivateAllProfiles();
            if (existing != null) {
                ServerProfile updated = new ServerProfile(
                        existing.getId(),
                        info.name != null ? info.name : existing.getName(),
                        resolvedUrl,
                        LRRAuthManager.getApiKey(),
                        true);
                EhDB.updateServerProfile(updated);
                LRRAuthManager.setActiveProfileId(existing.getId());
            } else {
                String profileName = info.name != null ? info.name : "LANraragi";
                ServerProfile newProfile = new ServerProfile(
                        0, profileName, resolvedUrl, LRRAuthManager.getApiKey(), true);
                long newId = EhDB.insertServerProfile(newProfile);
                LRRAuthManager.setActiveProfileId(newId);
            }
        }

        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            mConnecting = false;
            hideProgress();

            // Update input field with the resolved URL so user sees what worked
            if (mServerUrl != null) {
                mServerUrl.setText(resolvedUrl);
            }

            // Show server info panel
            if (mServerInfoPanel != null && mServerInfoText != null) {
                mServerInfoPanel.setVisibility(View.VISIBLE);
                String infoText = getString(R.string.lrr_server_info,
                        info.name != null ? info.name : "LANraragi",
                        info.version != null ? info.version : "?",
                        info.versionName != null ? info.versionName : "",
                        String.valueOf(info.archivesPerPage));
                mServerInfoText.setText(infoText);
            }

            Context ctx = getEHContext();
            if (ctx != null) {
                Toast.makeText(ctx, getString(R.string.lrr_connection_success,
                        info.name, info.version), Toast.LENGTH_SHORT).show();
            }

            if (navigateOnSuccess) {
                redirectToArchiveList();
            }

            // LANraragi: Warn if using HTTP on non-LAN address
            if (resolvedUrl.toLowerCase().startsWith("http://") && !LRRUrlHelper.isLanAddress(resolvedUrl)) {
                Toast.makeText(ctx != null ? ctx : getEHContext(),
                        R.string.lrr_security_warning,
                        Toast.LENGTH_LONG).show();
            }
        });
    }


    /**
     * Called on worker thread when all connection attempts fail.
     */
    private void onConnectFailure(Exception e) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            mConnecting = false;
            hideProgress();

            if (mServerInfoPanel != null) {
                mServerInfoPanel.setVisibility(View.GONE);
            }

            Context ctx = getEHContext();
            if (ctx != null) {
                String msg = LRRApiUtilsKt.friendlyError(e);
                Toast.makeText(ctx, getString(R.string.lrr_connection_failed, msg),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void redirectToArchiveList() {
        Bundle args = new Bundle();
        args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE);
        startSceneForCheckStep(CHECK_STEP_SIGN_IN, args);
        finish();
    }

    private void showProgress(boolean animation) {
        if (mProgress != null && View.VISIBLE != mProgress.getVisibility()) {
            if (animation) {
                mProgress.setAlpha(0.0f);
                mProgress.setVisibility(View.VISIBLE);
                mProgress.animate().alpha(1.0f).setDuration(500).start();
            } else {
                mProgress.setAlpha(1.0f);
                mProgress.setVisibility(View.VISIBLE);
            }
        }
    }

    private void hideProgress() {
        if (mProgress != null) {
            mProgress.setVisibility(View.GONE);
        }
    }
}
