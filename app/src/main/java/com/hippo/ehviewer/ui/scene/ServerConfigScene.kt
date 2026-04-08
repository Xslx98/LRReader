package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputLayout
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.LRRSecureStorageUnavailableException
import com.hippo.ehviewer.client.lrr.friendlyError
import com.hippo.ehviewer.client.lrr.LRRServerApi
import com.hippo.ehviewer.client.lrr.LRRUrlHelper
import com.hippo.ehviewer.client.lrr.data.LRRServerInfo
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer
import com.hippo.lib.yorozuya.ViewUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
class ServerConfigScene : SolidScene(), View.OnClickListener {

    private var mProgress: View? = null
    private var mServerUrlLayout: TextInputLayout? = null
    private var mApiKeyLayout: TextInputLayout? = null
    private var mServerUrl: EditText? = null
    private var mApiKey: EditText? = null
    private var mServerInfoPanel: LinearLayout? = null
    private var mServerInfoText: TextView? = null

    private var mConnecting = false

    override fun needShowLeftDrawer(): Boolean {
        // Show drawer when server is already configured (allows back navigation)
        return LRRAuthManager.isConfigured()
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_server_config, container, false)

        val configForm = ViewUtils.`$$`(view, R.id.config_form)
        mProgress = ViewUtils.`$$`(view, R.id.progress)
        mServerUrlLayout = ViewUtils.`$$`(configForm, R.id.server_url_layout) as? TextInputLayout
        mServerUrl = ViewUtils.`$$`(configForm, R.id.server_url) as? EditText
        mApiKeyLayout = ViewUtils.`$$`(configForm, R.id.api_key_layout) as? TextInputLayout
        mApiKey = ViewUtils.`$$`(configForm, R.id.api_key) as? EditText
        mServerInfoPanel = ViewUtils.`$$`(configForm, R.id.server_info_panel) as? LinearLayout
        mServerInfoText = ViewUtils.`$$`(configForm, R.id.server_info_text) as? TextView

        val testButton = ViewUtils.`$$`(configForm, R.id.test_connection)
        val connectButton = ViewUtils.`$$`(configForm, R.id.connect)

        testButton.setOnClickListener(this)
        connectButton.setOnClickListener(this)

        // Pre-fill existing settings
        val savedUrl = LRRAuthManager.getServerUrl()
        val savedKey = LRRAuthManager.getApiKey()
        if (savedUrl != null) {
            mServerUrl?.setText(savedUrl)
        }
        if (savedKey != null) {
            mApiKey?.setText(savedKey)
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mProgress = null
        mServerUrlLayout = null
        mApiKeyLayout = null
        mServerUrl = null
        mApiKey = null
        mServerInfoPanel = null
        mServerInfoText = null
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.test_connection -> doConnectionAttempt(false)
            R.id.connect -> doConnectionAttempt(true)
        }
    }

    /**
     * Get raw user input, trimmed and without trailing slash.
     */
    private fun getRawInput(): String? {
        return mServerUrl?.let { LRRUrlHelper.normalizeUrl(it.text.toString()) }
    }

    private fun getApiKeyInput(): String? {
        return mApiKey?.text?.toString()?.trim()
    }

    /**
     * Core connection method. Handles protocol auto-detection:
     *  1. If user typed explicit scheme -> use as-is
     *  2. If no scheme -> try https:// first, on failure try http://
     *
     * @param navigateOnSuccess if true, navigate to archive list on success (Connect button);
     *                          if false, just show server info (Test button)
     */
    private fun doConnectionAttempt(navigateOnSuccess: Boolean) {
        if (mConnecting) return

        val rawInput = getRawInput()
        if (TextUtils.isEmpty(rawInput)) {
            mServerUrlLayout?.error = getString(R.string.lrr_server_url_empty)
            return
        }
        mServerUrlLayout?.error = null

        hideSoftInput()
        showProgress(true)
        mConnecting = true

        // Save API key
        val apiKey = getApiKeyInput()
        try {
            LRRAuthManager.setApiKey(if (!TextUtils.isEmpty(apiKey)) apiKey else null)
        } catch (e: LRRSecureStorageUnavailableException) {
            showProgress(false)
            mConnecting = false
            showSecureStorageErrorDialog()
            return
        }

        val client = ServiceRegistry.networkModule.okHttpClient
        val testClient = LRRUrlHelper.buildTestClient(client)

        try {
            if (LRRUrlHelper.hasExplicitScheme(rawInput!!)) {
                // User specified protocol explicitly -- use as-is
                LRRAuthManager.setServerUrl(rawInput)
                tryConnect(testClient, rawInput, null, navigateOnSuccess)
            } else {
                // No explicit scheme: try HTTPS first, then fall back to HTTP
                val httpsUrl = "https://$rawInput"
                val httpUrl = "http://$rawInput"
                LRRAuthManager.setServerUrl(httpsUrl) // temp set for interceptor
                tryConnect(testClient, httpsUrl, httpUrl, navigateOnSuccess)
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            showProgress(false)
            mConnecting = false
            showSecureStorageErrorDialog()
        }
    }

    /**
     * Try connecting to primaryUrl. On failure, if fallbackUrl is non-null, try that too.
     */
    private fun tryConnect(
        client: OkHttpClient,
        primaryUrl: String,
        fallbackUrl: String?,
        navigateOnSuccess: Boolean
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // --- Attempt 1: primary URL ---
            try {
                Log.d(TAG, "Trying primary URL: $primaryUrl")
                val info = LRRServerApi.getServerInfo(client, primaryUrl)
                // Success on primary
                onConnectSuccess(primaryUrl, info, navigateOnSuccess)
                return@launch
            } catch (e1: Exception) {
                Log.d(TAG, "Primary URL failed: ${e1.message}")

                if (fallbackUrl == null) {
                    // No fallback -- report failure
                    onConnectFailure(e1)
                    return@launch
                }
            }

            // --- Attempt 2: fallback URL ---
            try {
                Log.d(TAG, "Trying fallback URL: $fallbackUrl")
                // Update interceptor URL for fallback attempt
                LRRAuthManager.setServerUrl(fallbackUrl)
                val info = LRRServerApi.getServerInfo(client, fallbackUrl)
                // Success on fallback
                onConnectSuccess(fallbackUrl, info, navigateOnSuccess)
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable during fallback", e)
                onConnectFailure(e)
            } catch (e2: Exception) {
                Log.d(TAG, "Fallback URL also failed: ${e2.message}")
                onConnectFailure(e2)
            }
        }
    }

    /**
     * Called from a coroutine on Dispatchers.IO when connection succeeds.
     * Persists/updates the [ServerProfile] then dispatches the UI updates back to main.
     */
    private suspend fun onConnectSuccess(
        resolvedUrl: String,
        info: LRRServerInfo,
        navigateOnSuccess: Boolean
    ) {
        try {
            LRRAuthManager.setServerUrl(resolvedUrl)
            LRRAuthManager.setServerName(info.name)

            // Create or update ServerProfile
            if (activity != null) {
                val existing = EhDB.findProfileByUrlAsync(resolvedUrl)
                EhDB.deactivateAllProfilesAsync()
                if (existing != null) {
                    // API key is stored in EncryptedSharedPreferences, not in Room
                    val updated = ServerProfile(
                        existing.id,
                        info.name ?: existing.name,
                        resolvedUrl,
                        true
                    )
                    EhDB.updateServerProfileAsync(updated)
                    LRRAuthManager.setApiKeyForProfile(existing.id, LRRAuthManager.getApiKey())
                    LRRAuthManager.setActiveProfileId(existing.id)
                } else {
                    val profileName = info.name ?: "LANraragi"
                    // API key is stored in EncryptedSharedPreferences, not in Room
                    val newProfile = ServerProfile(0, profileName, resolvedUrl, true)
                    val newId = EhDB.insertServerProfileAsync(newProfile)
                    LRRAuthManager.setApiKeyForProfile(newId, LRRAuthManager.getApiKey())
                    LRRAuthManager.setActiveProfileId(newId)
                }
            }
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable on connect success", e)
            onConnectFailure(e)
            return
        }

        if (activity == null) return
        requireActivity().runOnUiThread {
            mConnecting = false
            hideProgress()

            // Update input field with the resolved URL so user sees what worked
            mServerUrl?.setText(resolvedUrl)

            // Show server info panel
            if (mServerInfoPanel != null && mServerInfoText != null) {
                mServerInfoPanel!!.visibility = View.VISIBLE
                val infoText = getString(
                    R.string.lrr_server_info,
                    info.name ?: "LANraragi",
                    info.version ?: "?",
                    info.versionName ?: "",
                    info.archivesPerPage.toString()
                )
                mServerInfoText!!.text = infoText
            }

            val ctx = ehContext
            if (ctx != null) {
                Toast.makeText(
                    ctx,
                    getString(R.string.lrr_connection_success, info.name, info.version),
                    Toast.LENGTH_SHORT
                ).show()
            }

            if (navigateOnSuccess) {
                redirectToArchiveList()
            }

            // LANraragi: Warn if using HTTP on non-LAN address
            if (resolvedUrl.lowercase().startsWith("http://") && !LRRUrlHelper.isLanAddress(resolvedUrl)) {
                Toast.makeText(
                    ctx ?: ehContext,
                    R.string.lrr_security_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Called on worker thread when all connection attempts fail.
     */
    private fun onConnectFailure(e: Exception) {
        if (activity == null) return
        requireActivity().runOnUiThread {
            mConnecting = false
            hideProgress()

            mServerInfoPanel?.visibility = View.GONE

            if (e is LRRSecureStorageUnavailableException) {
                showSecureStorageErrorDialog()
                return@runOnUiThread
            }

            val ctx = ehContext
            if (ctx != null) {
                val msg = friendlyError(ctx, e)
                Toast.makeText(
                    ctx,
                    getString(R.string.lrr_connection_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSecureStorageErrorDialog() {
        val ctx = ehContext ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun redirectToArchiveList() {
        val args = Bundle().apply {
            putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
        }
        startSceneForCheckStep(CHECK_STEP_SIGN_IN, args)
        finish()
    }

    private fun showProgress(animation: Boolean) {
        val progress = mProgress ?: return
        if (View.VISIBLE != progress.visibility) {
            if (animation) {
                progress.alpha = 0.0f
                progress.visibility = View.VISIBLE
                progress.animate().alpha(1.0f).setDuration(500).start()
            } else {
                progress.alpha = 1.0f
                progress.visibility = View.VISIBLE
            }
        }
    }

    private fun hideProgress() {
        mProgress?.visibility = View.GONE
    }

    companion object {
        private const val TAG = "ServerConfigScene"
    }
}
