package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputLayout
import com.hippo.ehviewer.R
import com.hippo.ehviewer.util.collectFlow
import com.hippo.ehviewer.util.collectFlowWhileCreated
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.lib.yorozuya.ViewUtils

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
    private var mCleartextRow: View? = null
    private var mCleartextCheckbox: MaterialCheckBox? = null

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
        mCleartextRow = ViewUtils.`$$`(configForm, R.id.cleartext_row)
        mCleartextCheckbox =
            ViewUtils.`$$`(configForm, R.id.checkbox_allow_cleartext) as? MaterialCheckBox

        val testButton = ViewUtils.`$$`(configForm, R.id.test_connection)
        val connectButton = ViewUtils.`$$`(configForm, R.id.connect)

        testButton.setOnClickListener(this)
        connectButton.setOnClickListener(this)

        // Cleartext consent is only relevant for an explicit http:// URL — the
        // fallback gate exempts LAN and refuses WAN cleartext otherwise. Reveal
        // the checkbox exactly when the input is explicit http://, mirroring the
        // add/edit dialog.
        mServerUrl?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCleartextRowVisibility(s?.toString().orEmpty())
            }
        })

        // Pre-fill existing settings
        val savedUrl = LRRAuthManager.getServerUrl()
        val savedKey = LRRAuthManager.getApiKey()
        if (savedUrl != null) {
            mServerUrl?.setText(savedUrl)
        }
        if (savedKey != null) {
            mApiKey?.setText(savedKey)
        }
        updateCleartextRowVisibility(savedUrl.orEmpty())

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // View-scoped so the previous fragment-scoped collectors no longer stack
        // one set per re-attach. These three are replay=0 one-shot results of a
        // multi-second connection test that keeps running in the background, and
        // by the time they fire onConnectSuccess has already committed the global
        // config; losing them in a STOPPED window would leave the switched server
        // active with no navigation, error, or feedback. Collect for the whole
        // view lifetime (viewLifecycleOwner still cancels on view destroy).
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.connectSuccess) { result ->
            handleConnectSuccess(result)
        }
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.connectFailure) { e ->
            handleConnectFailure(e)
        }
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.secureStorageError) {
            hideProgress()
            showSecureStorageErrorDialog()
        }
        // connecting is a StateFlow whose current value replays on restart, so
        // the STARTED-gated default is fine here.
        collectFlow(viewLifecycleOwner, viewModel.connecting) { isConnecting ->
            if (!isConnecting) {
                hideProgress()
            }
        }
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
        mCleartextRow = null
        mCleartextCheckbox = null
    }

    private fun updateCleartextRowVisibility(text: String) {
        val isHttp = text.trim().lowercase().startsWith("http://")
        mCleartextRow?.visibility = if (isHttp) View.VISIBLE else View.GONE
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

        // Explicit http:// requires the user to opt into plain HTTP, matching
        // the add/edit dialog; otherwise the gate would refuse a non-LAN host
        // with no way to consent from this screen.
        val isHttp = rawInput!!.lowercase().startsWith("http://")
        if (isHttp && mCleartextCheckbox?.isChecked != true) {
            val ctx = ehContext
            if (ctx != null) {
                Toast.makeText(ctx, R.string.lrr_allow_cleartext_required, Toast.LENGTH_LONG).show()
            }
            return
        }
        val allowCleartext = mCleartextCheckbox?.isChecked == true

        hideSoftInput()
        showProgress(true)

        val apiKey = getApiKeyInput()
        viewModel.attemptConnection(rawInput, apiKey, navigateOnSuccess, allowCleartext)
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
        if (LRRUrlHelper.isInsecureWanUrl(resolvedUrl)) {
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
