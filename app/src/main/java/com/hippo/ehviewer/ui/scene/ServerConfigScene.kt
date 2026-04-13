package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.lib.yorozuya.ViewUtils
import kotlinx.coroutines.launch

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

    private lateinit var viewModel: ServerConfigViewModel

    private var mProgress: View? = null
    private var mServerUrlLayout: TextInputLayout? = null
    private var mApiKeyLayout: TextInputLayout? = null
    private var mServerUrl: EditText? = null
    private var mApiKey: EditText? = null
    private var mServerInfoPanel: LinearLayout? = null
    private var mServerInfoText: TextView? = null

    override fun needShowLeftDrawer(): Boolean {
        // Show drawer when server is already configured (allows back navigation)
        return LRRAuthManager.isConfigured()
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel = ViewModelProvider(requireActivity())[ServerConfigViewModel::class.java]

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

        // Observe ViewModel events
        lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            viewModel.connectSuccess.collect { result ->
                handleConnectSuccess(result)
            }
        }
        lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            viewModel.connectFailure.collect { e ->
                handleConnectFailure(e)
            }
        }
        lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            viewModel.secureStorageError.collect {
                hideProgress()
                showSecureStorageErrorDialog()
            }
        }
        lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            viewModel.connecting.collect { isConnecting ->
                if (!isConnecting) {
                    hideProgress()
                }
            }
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
     * Core connection method. Validates input and delegates to ViewModel.
     *
     * @param navigateOnSuccess if true, navigate to archive list on success (Connect button);
     *                          if false, just show server info (Test button)
     */
    private fun doConnectionAttempt(navigateOnSuccess: Boolean) {
        if (viewModel.connecting.value) return

        val rawInput = getRawInput()
        if (TextUtils.isEmpty(rawInput)) {
            mServerUrlLayout?.error = getString(R.string.lrr_server_url_empty)
            return
        }
        mServerUrlLayout?.error = null

        hideSoftInput()
        showProgress(true)

        val apiKey = getApiKeyInput()
        viewModel.attemptConnection(rawInput!!, apiKey, navigateOnSuccess)
    }

    /**
     * Called when ViewModel emits a successful connection result.
     */
    private fun handleConnectSuccess(result: ServerConfigViewModel.ConnectSuccess) {
        val info = result.serverInfo
        val resolvedUrl = result.resolvedUrl

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

        if (result.navigateOnSuccess) {
            redirectToArchiveList()
        }

        // LANraragi: Warn if using HTTP on non-LAN address
        if (viewModel.isInsecureWanConnection(resolvedUrl)) {
            Toast.makeText(
                ctx ?: ehContext,
                R.string.lrr_security_warning,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Called when ViewModel emits a connection failure.
     */
    private fun handleConnectFailure(e: Exception) {
        mServerInfoPanel?.visibility = View.GONE

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
}
