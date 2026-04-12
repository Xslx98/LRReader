package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRServerApi
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.data.LRRServerInfo
import com.lanraragi.reader.client.api.friendlyError
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer
import com.hippo.scene.StageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server list management scene. Shows all saved server profiles in a list.
 * Tap to switch, long-press for edit/delete, FAB to add new server.
 */
class ServerListScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mEmptyText: TextView? = null
    private var mAdapter: ServerAdapter? = null
    private var mReauthDialogShown = false
    private var mProfiles: MutableList<ServerProfile> = mutableListOf()

    // Snapshot of the list last dispatched to the adapter. Kept in sync with
    // mProfiles after EVERY notify*() call (full reload via DiffUtil, single
    // remove via notifyItemRemoved, single change via notifyItemChanged).
    // See docs/diffutil-root-cause-analysis.md for the snapshot ownership rule.
    private var mLastSnapshot: List<ServerProfile> = emptyList()

    override fun needShowLeftDrawer(): Boolean = true

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_server_list, container, false)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        mRecyclerView = view.findViewById(R.id.recycler_view)
        mEmptyText = view.findViewById(R.id.empty_text)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_add)

        mRecyclerView?.layoutManager = LinearLayoutManager(getEHContext())
        mAdapter = ServerAdapter()
        mRecyclerView?.adapter = mAdapter

        fab.setOnClickListener { showAddDialog() }

        loadProfiles()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from ServerConfigScene
        loadProfiles()
        // Prompt user if encrypted keystore is unavailable (credentials lost)
        if (LRRAuthManager.isNeedsReauthentication() && !mReauthDialogShown) {
            mReauthDialogShown = true
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.reauth_required_title)
                .setMessage(R.string.reauth_required_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showSecureStorageErrorDialog() {
        val ctx = getEHContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadProfiles() {
        val ctx = getEHContext() ?: return

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ArrayList(EhDB.getAllServerProfilesAsync()).also { list ->
                    list.sortWith(compareByDescending { it.isActive })
                }
            }
            val adapter = mAdapter
            if (adapter != null) {
                val diff = DiffUtil.calculateDiff(
                    ServerProfileDiffCallback(mLastSnapshot, result)
                )
                mProfiles = result
                mLastSnapshot = ArrayList(result)
                diff.dispatchUpdatesTo(adapter)
            } else {
                mProfiles = result
                mLastSnapshot = ArrayList(result)
            }
            mEmptyText?.visibility = if (mProfiles.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun switchToProfile(profile: ServerProfile) {
        val ctx = getEHContext() ?: return

        // DB writes on IO thread, then UI updates on main thread
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                EhDB.deactivateAllProfilesAsync()
                EhDB.updateServerProfileAsync(
                    ServerProfile(
                        id = profile.id,
                        name = profile.name,
                        url = profile.url,
                        isActive = true
                    )
                )
            }
            switchToProfileUiUpdate(profile)
        }
    }

    private fun switchToProfileUiUpdate(profile: ServerProfile) {
        val ctx = getEHContext() ?: return

        // Update LRRAuthManager
        try {
            LRRAuthManager.setServerUrl(profile.url)
            val profileApiKey = LRRAuthManager.getApiKeyForProfile(profile.id)
            LRRAuthManager.setApiKey(profileApiKey)
            LRRAuthManager.setServerName(profile.name)
            LRRAuthManager.setActiveProfileId(profile.id)
            LRRAuthManager.setAllowCleartext(profile.allowCleartext)
            LRRAuthManager.bumpServerConfigVersion()
        } catch (e: LRRSecureStorageUnavailableException) {
            showSecureStorageErrorDialog()
            return
        }

        // Clear caches so previous server's content doesn't appear under new server
        ServiceRegistry.clearAllCaches()

        // Reload download manager on main thread (repo.assertMainThread)
        ServiceRegistry.dataModule.downloadManager.reload()

        Toast.makeText(
            ctx,
            getString(R.string.lrr_server_switched, profile.name),
            Toast.LENGTH_SHORT
        ).show()

        // Warn if switching to HTTP on a public address
        val url = profile.url
        if (url.lowercase().startsWith("http://") && !LRRUrlHelper.isLanAddress(url)) {
            Toast.makeText(ctx, R.string.lrr_security_warning, Toast.LENGTH_LONG).show()
        }

        // Navigate to archive list
        val args = Bundle().apply {
            putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
        }
        val act = activity
        if (act is StageActivity) {
            act.startSceneFirstly(Announcer(GalleryListScene::class.java).setArgs(args))
        }
    }

    private fun deleteProfile(profile: ServerProfile, position: Int) {
        val ctx = getEHContext() ?: return

        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_delete_server)
            .setMessage(getString(R.string.lrr_delete_server_confirm, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    LRRAuthManager.clearApiKeyForProfile(profile.id)
                } catch (e: LRRSecureStorageUnavailableException) {
                    showSecureStorageErrorDialog()
                    return@setPositiveButton
                }
                ServiceRegistry.coroutineModule.ioScope.launch {
                    EhDB.deleteServerProfileAsync(profile)
                }
                mProfiles.removeAt(position)
                mLastSnapshot = ArrayList(mProfiles)
                mAdapter?.notifyItemRemoved(position)
                mEmptyText?.visibility = if (mProfiles.isEmpty()) View.VISIBLE else View.GONE
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== Adapter =====

    private inner class ServerAdapter : RecyclerView.Adapter<ServerViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server_profile, parent, false)
            return ServerViewHolder(v)
        }

        override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
            val profile = mProfiles[position]
            holder.name.text = profile.name
            holder.url.text = profile.url

            // Highlight the currently active/connected server
            if (profile.isActive) {
                holder.activeIcon.visibility = View.VISIBLE
                holder.name.setTextColor(
                    holder.itemView.context.resources.getColor(R.color.colorPrimary)
                )
                holder.itemView.setBackgroundColor(Color.parseColor("#1A4CAF50")) // subtle green tint
            } else {
                holder.activeIcon.visibility = View.INVISIBLE
                holder.name.setTextColor(Color.parseColor("#999999"))
                holder.url.setTextColor(Color.parseColor("#BBBBBB"))
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.itemView.setOnClickListener { switchToProfile(profile) }
            holder.itemView.setOnLongClickListener {
                showProfileOptions(profile, position)
                true
            }
        }

        override fun getItemCount(): Int = mProfiles.size
    }

    private class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.server_name)
        val url: TextView = itemView.findViewById(R.id.server_url)
        val activeIcon: ImageView = itemView.findViewById(R.id.icon_active)
    }

    private fun showProfileOptions(profile: ServerProfile, position: Int) {
        val ctx = getEHContext() ?: return

        val options = arrayOf(
            getString(R.string.lrr_edit_server),
            getString(R.string.lrr_delete_server)
        )

        AlertDialog.Builder(ctx)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(profile, position)
                    1 -> deleteProfile(profile, position)
                }
            }
            .show()
    }

    private fun showEditDialog(profile: ServerProfile, position: Int) {
        val ctx = getEHContext() ?: return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null)
        val nameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_name)
        val urlEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_url)
        val apiKeyEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_api_key)
        val cleartextRow = dialogView.findViewById<LinearLayout>(R.id.cleartext_row)
        val cleartextCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_allow_cleartext)

        // Pre-fill current values
        nameEdit.setText(profile.name)
        urlEdit.setText(profile.url)
        val existingKey = LRRAuthManager.getApiKeyForProfile(profile.id)
        if (existingKey != null) {
            apiKeyEdit.setText(existingKey)
        }
        // Pre-fill cleartext checkbox from the existing profile, then show/hide based on the URL scheme.
        cleartextCheckbox.isChecked = profile.allowCleartext
        cleartextRow.visibility =
            if (profile.url.lowercase().startsWith("http://")) View.VISIBLE else View.GONE

        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty().lowercase()
                cleartextRow.visibility = if (text.startsWith("http://")) View.VISIBLE else View.GONE
            }
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.lrr_edit_server_save, null) // set below to prevent auto-dismiss
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Prevent the dialog's keyboard from resizing the underlying Activity layout.
        // Save the original mode so we can restore it when the dialog is dismissed.
        val originalSoftInputMode: Int = if (activity?.window != null) {
            val mode = requireActivity().window.attributes.softInputMode
            requireActivity().window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
            mode
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }

        dialog.setOnDismissListener {
            // Restore the Activity's original soft input mode
            activity?.window?.setSoftInputMode(originalSoftInputMode)
        }

        dialog.show()

        // Override positive button to add validation
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = nameEdit.text?.toString()?.trim().orEmpty()
            val newUrl = urlEdit.text?.toString()?.trim().orEmpty()
            val newKey = apiKeyEdit.text?.toString()?.trim().orEmpty()

            if (newName.isEmpty()) {
                nameEdit.error = getString(R.string.name_is_empty)
                return@setOnClickListener
            }
            if (newUrl.isEmpty()) {
                urlEdit.error = getString(R.string.lrr_server_url_empty)
                return@setOnClickListener
            }

            val normalizedInput = LRRUrlHelper.normalizeUrl(newUrl)

            // Validate cleartext consent for explicit http:// URLs
            if (normalizedInput.lowercase().startsWith("http://")
                && !cleartextCheckbox.isChecked
            ) {
                Toast.makeText(ctx, R.string.lrr_allow_cleartext_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Helper: persist profile with the resolved URL and dismiss
            fun saveAndDismiss(resolvedUrl: String) {
                val isHttpUrl = resolvedUrl.lowercase().startsWith("http://")
                val updated = ServerProfile(
                    id = profile.id,
                    name = newName,
                    url = resolvedUrl,
                    isActive = profile.isActive,
                    // HTTP fallback: allow cleartext for the resolved HTTP URL
                    allowCleartext = if (isHttpUrl) true else true
                )
                val isActive = profile.isActive
                ServiceRegistry.coroutineModule.ioScope.launch {
                    EhDB.updateServerProfileAsync(updated)
                    try {
                        LRRAuthManager.setApiKeyForProfile(profile.id, newKey.ifEmpty { null })
                        if (isActive) {
                            LRRAuthManager.setServerUrl(updated.url)
                            LRRAuthManager.setApiKey(newKey.ifEmpty { null })
                            LRRAuthManager.setServerName(newName)
                            LRRAuthManager.setAllowCleartext(updated.allowCleartext)
                            LRRAuthManager.bumpServerConfigVersion()
                        }
                        LRRAuthManager.markReauthIfProfilesUnprotected(
                            EhDB.getAllServerProfilesAsync().map { it.id }
                        )
                    } catch (e: LRRSecureStorageUnavailableException) {
                        activity?.runOnUiThread { showSecureStorageErrorDialog() }
                        return@launch
                    }
                }
                mProfiles[position] = updated
                mLastSnapshot = ArrayList(mProfiles)
                mAdapter?.notifyItemChanged(position)
                dialog.dismiss()
            }

            // Always test connection before saving
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show()

            try {
                LRRAuthManager.setApiKey(newKey.ifEmpty { null })
            } catch (e: LRRSecureStorageUnavailableException) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                showSecureStorageErrorDialog()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val testClient = LRRUrlHelper.buildTestClient(
                    ServiceRegistry.networkModule.okHttpClient
                )
                LRRUrlHelper.connectWithFallback(
                    testClient,
                    normalizedInput,
                    object : LRRUrlHelper.ConnectCallback {
                        override fun onSuccess(
                            resolvedUrl: String,
                            info: LRRServerInfo,
                            usedHttpFallback: Boolean
                        ) {
                            activity?.runOnUiThread {
                                urlEdit.setText(resolvedUrl)
                                saveAndDismiss(resolvedUrl)
                                if (usedHttpFallback) {
                                    Toast.makeText(
                                        ctx,
                                        R.string.lrr_https_fallback_warning,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        override fun onFailure(error: Exception) {
                            activity?.runOnUiThread {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                Toast.makeText(
                                    ctx,
                                    getString(
                                        R.string.lrr_connection_failed,
                                        friendlyError(ctx, error)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Show a dialog to add a new server profile.
     * Reuses dialog_edit_server.xml layout. Tests connection with HTTPS->HTTP
     * fallback, then saves and switches to the new server.
     */
    private fun showAddDialog() {
        val ctx = getEHContext() ?: return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null)
        val nameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_name)
        val urlEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_url)
        val apiKeyEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_api_key)
        val cleartextRow = dialogView.findViewById<LinearLayout>(R.id.cleartext_row)
        val cleartextCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_allow_cleartext)

        // New profiles: checkbox unchecked so users must explicitly opt in to HTTP.
        // For https:// profiles the flag is set to true on save (interceptor ignores it).
        cleartextCheckbox.isChecked = false
        cleartextRow.visibility = View.GONE

        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty().lowercase()
                cleartextRow.visibility = if (text.startsWith("http://")) View.VISIBLE else View.GONE
            }
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.lrr_save_and_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEdit.text?.toString()?.trim().orEmpty()
            val url = urlEdit.text?.toString()?.trim().orEmpty()
            val apiKey = apiKeyEdit.text?.toString()?.trim().orEmpty()

            if (name.isEmpty()) {
                nameEdit.error = getString(R.string.name_is_empty)
                return@setOnClickListener
            }
            if (url.isEmpty()) {
                urlEdit.error = getString(R.string.lrr_server_url_empty)
                return@setOnClickListener
            }

            // Normalize: trim + strip trailing slashes
            val normalizedInput = LRRUrlHelper.normalizeUrl(url)
            val finalKey: String? = apiKey.ifEmpty { null }

            // Validate cleartext consent before attempting connection.
            val isHttpUrl = normalizedInput.lowercase().startsWith("http://")
            if (isHttpUrl && !cleartextCheckbox.isChecked) {
                Toast.makeText(ctx, R.string.lrr_allow_cleartext_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val profileAllowCleartext = if (isHttpUrl) cleartextCheckbox.isChecked else true

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show()

            // Temporarily set auth for the interceptor
            val oldUrl: String? = LRRAuthManager.getServerUrl()
            val oldKey: String? = LRRAuthManager.getApiKey()
            try {
                LRRAuthManager.setApiKey(finalKey)
            } catch (e: LRRSecureStorageUnavailableException) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                showSecureStorageErrorDialog()
                return@setOnClickListener
            }

            // Build a test client with short timeouts
            val baseClient = ServiceRegistry.networkModule.okHttpClient
            val testClient = LRRUrlHelper.buildTestClient(baseClient)

            lifecycleScope.launch(Dispatchers.IO) {
                // Use HTTPS->HTTP fallback logic
                LRRUrlHelper.connectWithFallback(
                    testClient,
                    normalizedInput,
                    object : LRRUrlHelper.ConnectCallback {
                        override fun onSuccess(
                            resolvedUrl: String,
                            info: LRRServerInfo,
                            usedHttpFallback: Boolean
                        ) {
                            if (activity == null) return
                            requireActivity().runOnUiThread {
                                // DB operations on IO thread
                                val resolvedIsHttp = resolvedUrl.lowercase().startsWith("http://")
                                val savedAllowCleartext = if (resolvedIsHttp) profileAllowCleartext else true

                                // Set auth immediately (in-memory via .apply())
                                try {
                                    LRRAuthManager.setServerUrl(resolvedUrl)
                                    LRRAuthManager.setApiKey(finalKey)
                                    LRRAuthManager.setServerName(name)
                                    LRRAuthManager.setAllowCleartext(savedAllowCleartext)
                                    LRRAuthManager.bumpServerConfigVersion()
                                } catch (e: LRRSecureStorageUnavailableException) {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    showSecureStorageErrorDialog()
                                    return@runOnUiThread
                                }
                                ServiceRegistry.coroutineModule.ioScope.launch {
                                    EhDB.deactivateAllProfilesAsync()
                                    val newProfile = ServerProfile(
                                        id = 0,
                                        name = name,
                                        url = resolvedUrl,
                                        isActive = true,
                                        allowCleartext = savedAllowCleartext
                                    )
                                    val newId = EhDB.insertServerProfileAsync(newProfile)
                                    try {
                                        LRRAuthManager.setApiKeyForProfile(newId, finalKey)
                                        LRRAuthManager.setActiveProfileId(newId)
                                    } catch (e: LRRSecureStorageUnavailableException) {
                                        activity?.runOnUiThread { showSecureStorageErrorDialog() }
                                        return@launch
                                    }
                                    // reload() requires main thread
                                    activity?.runOnUiThread {
                                        ServiceRegistry.dataModule.downloadManager.reload()
                                    }
                                }

                                Toast.makeText(
                                    ctx,
                                    getString(
                                        R.string.lrr_connection_success,
                                        info.name,
                                        info.version
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()

                                // Warn on HTTP fallback or insecure public address
                                if (usedHttpFallback) {
                                    Toast.makeText(
                                        ctx,
                                        R.string.lrr_https_fallback_warning,
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else if (resolvedUrl.lowercase().startsWith("http://") && !LRRUrlHelper.isLanAddress(resolvedUrl)) {
                                    Toast.makeText(
                                        ctx,
                                        R.string.lrr_security_warning,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                dialog.dismiss()
                                loadProfiles()

                                // Navigate to archive list
                                val args = Bundle().apply {
                                    putString(
                                        GalleryListScene.KEY_ACTION,
                                        GalleryListScene.ACTION_HOMEPAGE
                                    )
                                }
                                val act = activity
                                if (act is StageActivity) {
                                    act.startSceneFirstly(
                                        Announcer(GalleryListScene::class.java).setArgs(args)
                                    )
                                }
                            }
                        }

                        override fun onFailure(error: Exception) {
                            // Restore old auth on failure. If secure storage became
                            // unavailable mid-flow, we cannot restore — log and continue
                            // so the UI still surfaces the original connect error.
                            try {
                                oldUrl?.let { LRRAuthManager.setServerUrl(it) }
                                LRRAuthManager.setApiKey(oldKey)
                            } catch (_: LRRSecureStorageUnavailableException) {
                                // Secure storage is down — the original connect failure
                                // already reflects this; proceed with its dialog below.
                            }

                            if (activity == null) return
                            requireActivity().runOnUiThread {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                Toast.makeText(
                                    ctx,
                                    getString(
                                        R.string.lrr_connection_failed,
                                        friendlyError(ctx, error)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Test connection to the active server after profile edits.
     * Called from a background thread; shows a Toast on failure.
     */
    private suspend fun verifyActiveProfile(url: String) {
        val ctx = getEHContext() ?: return
        val testClient = LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)
        try {
            LRRServerApi.getServerInfo(testClient, url)
        } catch (e: Exception) {
            val msg = friendlyError(ctx, e)
            activity?.runOnUiThread {
                Toast.makeText(
                    ctx,
                    getString(R.string.lrr_connection_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mEmptyText = null
        mAdapter = null
        // Reset snapshot so the next view recreation starts from a clean baseline.
        mLastSnapshot = emptyList()
    }

    /**
     * DiffUtil callback for ServerProfile lists. Identity is the Room PK `id`.
     * Content compares all fields rendered in onBindViewHolder so that an edit
     * (rename, URL change, active toggle) repaints the affected row.
     */
    private class ServerProfileDiffCallback(
        private val oldList: List<ServerProfile>,
        private val newList: List<ServerProfile>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.name == n.name && o.url == n.url && o.isActive == n.isActive
        }
    }
}
