package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.LRRUrlHelper
import com.hippo.ehviewer.client.lrr.data.LRRServerInfo
import com.hippo.ehviewer.client.lrr.friendlyError
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer
import com.hippo.scene.StageActivity
import com.hippo.util.IoThreadPoolExecutor

/**
 * Server list management scene. Shows all saved server profiles in a list.
 * Tap to switch, long-press for edit/delete, FAB to add new server.
 */
class ServerListScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mEmptyText: TextView? = null
    private var mAdapter: ServerAdapter? = null
    private var mProfiles: MutableList<ServerProfile> = mutableListOf()

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
    }

    private fun loadProfiles() {
        val ctx = getEHContext() ?: return

        IoThreadPoolExecutor.instance.execute {
            val result = ArrayList(EhDB.getAllServerProfiles())
            result.sortWith(compareByDescending { it.isActive })
            requireActivity().runOnUiThread {
                mProfiles = result
                mAdapter?.notifyDataSetChanged()
                mEmptyText?.visibility = if (mProfiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun switchToProfile(profile: ServerProfile) {
        val ctx = getEHContext() ?: return

        // DB writes on IO thread, then UI updates on main thread
        IoThreadPoolExecutor.instance.execute {
            EhDB.deactivateAllProfiles()
            EhDB.updateServerProfile(
                ServerProfile(
                    id = profile.id,
                    name = profile.name,
                    url = profile.url,
                    isActive = true
                )
            )
            activity?.runOnUiThread { switchToProfileUiUpdate(profile) }
        }
    }

    private fun switchToProfileUiUpdate(profile: ServerProfile) {
        val ctx = getEHContext() ?: return

        // Update LRRAuthManager
        LRRAuthManager.setServerUrl(profile.url)
        val profileApiKey = LRRAuthManager.getApiKeyForProfile(profile.id)
        LRRAuthManager.setApiKey(profileApiKey)
        LRRAuthManager.setServerName(profile.name)
        LRRAuthManager.setActiveProfileId(profile.id)

        // Clear caches so previous server's content doesn't appear under new server
        ServiceRegistry.clearAllCaches()

        // Reload download manager on IO thread (EhDB.getAllDownloadInfo blocks)
        IoThreadPoolExecutor.instance.execute {
            ServiceRegistry.dataModule.downloadManager.reload()
        }

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
                LRRAuthManager.clearApiKeyForProfile(profile.id)
                IoThreadPoolExecutor.instance.execute {
                    EhDB.deleteServerProfile(profile)
                }
                mProfiles.removeAt(position)
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

        // Pre-fill current values
        nameEdit.setText(profile.name)
        urlEdit.setText(profile.url)
        val existingKey = LRRAuthManager.getApiKeyForProfile(profile.id)
        if (existingKey != null) {
            apiKeyEdit.setText(existingKey)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.lrr_edit_server_save, null) // set below to prevent auto-dismiss
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Prevent the dialog's keyboard from resizing the underlying Activity layout.
        // Save the original mode so we can restore it when the dialog is dismissed.
        val originalSoftInputMode: Int = if (activity?.window != null) {
            val mode = activity!!.window.attributes.softInputMode
            activity!!.window.setSoftInputMode(
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

            // Normalize URL: add protocol if missing, strip trailing slashes
            var normalizedUrl = LRRUrlHelper.normalizeUrl(newUrl)
            if (!LRRUrlHelper.hasExplicitScheme(normalizedUrl)) {
                // Default to https:// for bare hostnames
                normalizedUrl = "https://$normalizedUrl"
            }

            // Save to DB on IO thread
            val updated = ServerProfile(
                id = profile.id,
                name = newName,
                url = normalizedUrl,
                isActive = profile.isActive
            )
            IoThreadPoolExecutor.instance.execute {
                EhDB.updateServerProfile(updated)
                LRRAuthManager.setApiKeyForProfile(profile.id, newKey.ifEmpty { null })
                if (profile.isActive) {
                    LRRAuthManager.setServerUrl(updated.url)
                    LRRAuthManager.setApiKey(newKey.ifEmpty { null })
                    LRRAuthManager.setServerName(newName)
                }
            }

            // Refresh list immediately with in-memory data
            mProfiles[position] = updated
            mAdapter?.notifyItemChanged(position)
            dialog.dismiss()
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

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show()

            // Temporarily set auth for the interceptor
            val oldUrl: String? = LRRAuthManager.getServerUrl()
            val oldKey: String? = LRRAuthManager.getApiKey()
            LRRAuthManager.setApiKey(finalKey)

            // Build a test client with short timeouts
            val baseClient = ServiceRegistry.networkModule.okHttpClient
            val testClient = LRRUrlHelper.buildTestClient(baseClient)

            IoThreadPoolExecutor.instance.execute {
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
                            activity!!.runOnUiThread {
                                // Set auth immediately (in-memory via .apply())
                                LRRAuthManager.setServerUrl(resolvedUrl)
                                LRRAuthManager.setApiKey(finalKey)
                                LRRAuthManager.setServerName(name)

                                // DB operations on IO thread
                                IoThreadPoolExecutor.instance.execute {
                                    EhDB.deactivateAllProfiles()
                                    val newProfile = ServerProfile(
                                        id = 0,
                                        name = name,
                                        url = resolvedUrl,
                                        isActive = true
                                    )
                                    val newId = EhDB.insertServerProfile(newProfile)
                                    LRRAuthManager.setApiKeyForProfile(newId, finalKey)
                                    LRRAuthManager.setActiveProfileId(newId)
                                    ServiceRegistry.dataModule.downloadManager.reload()
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

                                // Warn if connected via HTTP on a public address
                                if (usedHttpFallback && !LRRUrlHelper.isLanAddress(resolvedUrl)) {
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
                            // Restore old auth on failure
                            oldUrl?.let { LRRAuthManager.setServerUrl(it) }
                            LRRAuthManager.setApiKey(oldKey)

                            if (activity == null) return
                            activity!!.runOnUiThread {
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

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mEmptyText = null
        mAdapter = null
    }
}
