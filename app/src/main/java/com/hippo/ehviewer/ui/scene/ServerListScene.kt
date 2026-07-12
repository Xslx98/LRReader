package com.hippo.ehviewer.ui.scene

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.util.collectFlow
import com.hippo.ehviewer.util.collectFlowWhileCreated
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.friendlyError
import com.hippo.ehviewer.dao.ServerProfile
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer
import com.hippo.scene.StageActivity

/**
 * Server list management scene. Shows all saved server profiles in a list.
 * Tap to switch, long-press for edit/delete, FAB to add new server.
 *
 * Business logic (CRUD, connection testing, cache clearing) lives in
 * [ServerListViewModel]. Dialog construction lives in [ServerListDialogHelper].
 * This Scene retains: view inflation, adapter/RecyclerView setup, ViewModel
 * observation, and navigation.
 */
class ServerListScene : BaseScene() {

    private lateinit var viewModel: ServerListViewModel
    private lateinit var dialogHelper: ServerListDialogHelper

    private var mRecyclerView: RecyclerView? = null
    private var mEmptyText: TextView? = null
    private var mAdapter: ServerAdapter? = null
    private var mReauthDialogShown = false

    // Local copy for adapter binding; kept in sync with ViewModel's profiles.
    private var mProfiles: List<ServerProfile> = emptyList()

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
        viewModel = ViewModelProvider(requireActivity())[ServerListViewModel::class.java]
        dialogHelper = ServerListDialogHelper(
            contextProvider = { getEHContext() },
            activityProvider = { activity as? androidx.fragment.app.FragmentActivity },
            stringProvider = { resId -> getString(resId) },
            stringFormatProvider = { resId, arg -> getString(resId, arg) },
            viewModel = viewModel
        )

        val view = inflater.inflate(R.layout.scene_server_list, container, false)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        mRecyclerView = view.findViewById(R.id.recycler_view)
        mEmptyText = view.findViewById(R.id.empty_text)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_add)

        mRecyclerView?.layoutManager = LinearLayoutManager(getEHContext())
        mAdapter = ServerAdapter()
        mRecyclerView?.adapter = mAdapter

        fab.setOnClickListener { dialogHelper.showAddDialog() }

        viewModel.loadProfiles()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from ServerConfigScene
        viewModel.loadProfiles()
        // Prompt user if encrypted keystore is unavailable (credentials lost)
        if (LRRAuthManager.isNeedsReauthentication() && !mReauthDialogShown) {
            mReauthDialogShown = true
            dialogHelper.showReauthDialog()
        }
    }

    // ===== ViewModel observation =====

    private fun observeViewModel() {
        // UI-4: view-scoped + STARTED-gated. The previous fragment-scoped
        // collectors stacked one set per re-attach — applyProfileSwitch
        // (cache clear + reload + navigation) ran once per accumulated
        // collector for a single ProfileActivated event.
        collectFlow(viewLifecycleOwner, viewModel.profiles) { profiles ->
            val adapter = mAdapter
            if (adapter != null) {
                val diff = DiffUtil.calculateDiff(
                    ServerProfileDiffCallback(mLastSnapshot, profiles)
                )
                mProfiles = profiles
                mLastSnapshot = ArrayList(profiles)
                diff.dispatchUpdatesTo(adapter)
            } else {
                mProfiles = profiles
                mLastSnapshot = ArrayList(profiles)
            }
            mEmptyText?.visibility = if (mProfiles.isEmpty()) View.VISIBLE else View.GONE
        }

        // Observe one-shot UI events. These are replay=0 events that drive
        // state-machine transitions (ProfileActivated → applyProfileSwitch,
        // ProfileAdded/EditSaved → navigation/dialog dismissal); losing one in a
        // STOPPED window would split Room's active flag from LRRAuthManager or
        // soft-lock the add/edit dialog, so collect for the whole view lifetime.
        // viewLifecycleOwner scoping still cancels on view destroy, so no
        // duplicate collector stacks across re-attach.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.uiEvent) { event ->
            handleUiEvent(event)
        }
    }

    private fun handleUiEvent(event: ServerListViewModel.ServerListUiEvent) {
        val ctx = getEHContext() ?: return
        when (event) {
            is ServerListViewModel.ServerListUiEvent.ShowToast -> {
                Toast.makeText(ctx, event.message, Toast.LENGTH_LONG).show()
            }

            is ServerListViewModel.ServerListUiEvent.ShowToastRes -> {
                Toast.makeText(ctx, event.resId, Toast.LENGTH_LONG).show()
            }

            is ServerListViewModel.ServerListUiEvent.SecureStorageError -> {
                dialogHelper.showSecureStorageErrorDialog()
            }

            is ServerListViewModel.ServerListUiEvent.ProfileActivated -> {
                applyProfileSwitch(event.profile)
            }

            is ServerListViewModel.ServerListUiEvent.ProfileAdded -> {
                handleProfileAdded(event)
            }

            is ServerListViewModel.ServerListUiEvent.EditSaved -> {
                dialogHelper.currentEditDialog?.dismiss()
                dialogHelper.clearDialogRefs()
            }

            is ServerListViewModel.ServerListUiEvent.EditConnectionFailed -> {
                dialogHelper.currentEditDialog
                    ?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.isEnabled = true
                Toast.makeText(
                    ctx,
                    getString(R.string.lrr_connection_failed, friendlyError(ctx, event.cause)),
                    Toast.LENGTH_LONG
                ).show()
            }

            is ServerListViewModel.ServerListUiEvent.AddConnectionFailed -> {
                dialogHelper.currentAddDialog
                    ?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.isEnabled = true
                Toast.makeText(
                    ctx,
                    getString(R.string.lrr_connection_failed, friendlyError(ctx, event.cause)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ===== Profile switch (UI side) =====

    private fun applyProfileSwitch(profile: ServerProfile) {
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
            dialogHelper.showSecureStorageErrorDialog()
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
        if (LRRUrlHelper.isInsecureWanUrl(profile.url)) {
            Toast.makeText(ctx, R.string.lrr_security_warning, Toast.LENGTH_LONG).show()
        }

        navigateToGalleryList()
    }

    // ===== Profile add (UI side) =====

    private fun handleProfileAdded(event: ServerListViewModel.ServerListUiEvent.ProfileAdded) {
        val ctx = getEHContext() ?: return

        // Reload download manager on main thread
        ServiceRegistry.dataModule.downloadManager.reload()

        Toast.makeText(
            ctx,
            getString(R.string.lrr_connection_success, event.info.name, event.info.version),
            Toast.LENGTH_SHORT
        ).show()

        if (event.usedHttpFallback) {
            Toast.makeText(ctx, R.string.lrr_https_fallback_warning, Toast.LENGTH_LONG).show()
        } else if (LRRUrlHelper.isInsecureWanUrl(event.resolvedUrl)) {
            Toast.makeText(ctx, R.string.lrr_security_warning, Toast.LENGTH_LONG).show()
        }

        dialogHelper.currentAddDialog?.dismiss()
        dialogHelper.clearDialogRefs()

        navigateToGalleryList()
    }

    // ===== Navigation =====

    private fun navigateToGalleryList() {
        val args = Bundle().apply {
            putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
        }
        val act = activity
        if (act is StageActivity) {
            act.startSceneFirstly(Announcer(GalleryListScene::class.java).setArgs(args))
        }
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

            // Highlight the currently active/connected server. Colors resolve from
            // the ACTIVITY THEME (manual in-app setting), never from -night
            // resources — the manual switcher uses Activity.setTheme, so -night
            // would follow the system dark mode instead (RES-4). Inactive rows are
            // normal list items: name = theme textColorPrimary; the URL keeps the
            // layout's textColorSecondary (no code override) for both branches.
            val ctx = holder.itemView.context
            if (profile.isActive) {
                holder.activeIcon.visibility = View.VISIBLE
                holder.name.setTextColor(
                    AttrResources.getAttrColor(ctx, R.attr.serverActiveNameColor)
                )
                holder.itemView.setBackgroundColor(
                    AttrResources.getAttrColor(ctx, R.attr.serverActiveRowBg)
                )
            } else {
                holder.activeIcon.visibility = View.INVISIBLE
                holder.name.setTextColor(
                    AttrResources.getAttrColor(ctx, android.R.attr.textColorPrimary)
                )
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.itemView.setOnClickListener { viewModel.activateProfile(profile) }
            holder.itemView.setOnLongClickListener {
                dialogHelper.showProfileOptions(profile, position) { p, pos ->
                    dialogHelper.showEditDialog(p, pos)
                }
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

    // ===== DiffUtil =====

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

    // ===== Lifecycle =====

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mEmptyText = null
        mAdapter = null
        mLastSnapshot = emptyList()
        dialogHelper.clearDialogRefs()
    }
}
