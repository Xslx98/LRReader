package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.ui.scene.TankoubonsViewModel.TankUiEvent
import com.hippo.ehviewer.util.collectFlow
import com.hippo.scene.Announcer
import com.hippo.widget.LoadImageViewNew
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.TankoubonSupportGate

/**
 * Scene that displays LANraragi tankoubons (ordered archive collections)
 * with full CRUD support. Clicking a tankoubon navigates to
 * [TankoubonDetailScene] showing its member archives.
 *
 * Business logic (API calls) is delegated to [TankoubonsViewModel].
 * This Scene retains view construction, adapter, dialogs, and navigation.
 */
class TankoubonsScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mProgress: View? = null
    private var mErrorView: View? = null
    private var mErrorIcon: ImageView? = null
    private var mErrorTitle: TextView? = null
    private var mErrorMessage: TextView? = null
    private var mErrorRetry: TextView? = null
    private var mToolbar: MaterialToolbar? = null

    private val mTanks: MutableList<LRRTankoubonApi.Tankoubon> = mutableListOf()
    private var mAdapter: TankoubonAdapter? = null

    /** Active server base URL captured at view creation; used for thumb URLs. */
    private var mServerUrl: String? = null

    private lateinit var viewModel: TankoubonsViewModel

    // Snapshot of the list last dispatched to the adapter. Read/written ONLY by
    // the tanks observer (the single dispatch path — every CRUD op re-fetches).
    // See docs/diffutil-root-cause-analysis.md for the snapshot ownership rule.
    private var mLastSnapshot: List<LRRTankoubonApi.Tankoubon> = emptyList()

    override fun getNavCheckedItem(): Int = R.id.nav_tankoubons

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_tankoubons, container, false)

        viewModel = ViewModelProvider(requireActivity())[TankoubonsViewModel::class.java]
        mServerUrl = LRRAuthManager.getServerUrl()

        mToolbar = view.findViewById(R.id.toolbar)
        mProgress = view.findViewById(R.id.progress)
        mErrorView = view.findViewById(R.id.error_view)
        mErrorIcon = view.findViewById(R.id.error_icon)
        mErrorTitle = view.findViewById(R.id.error_title)
        mErrorMessage = view.findViewById(R.id.error_message)
        mErrorRetry = view.findViewById(R.id.error_retry)
        mRecyclerView = view.findViewById(R.id.recycler_view)

        mToolbar?.apply {
            setTitle(R.string.tankoubons_title)
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
            inflateMenu(R.menu.scene_tankoubons)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_add_tankoubon) {
                    showCreateDialog()
                    true
                } else {
                    false
                }
            }
        }

        mAdapter = TankoubonAdapter()
        mRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = mAdapter
        }

        // Observe tankoubon list from ViewModel
        collectFlow(viewLifecycleOwner, viewModel.tanks) { newList ->
            val adapter = mAdapter
            if (adapter != null) {
                val diff = DiffUtil.calculateDiff(
                    TankoubonDiffCallback(mLastSnapshot, newList)
                )
                mTanks.clear()
                mTanks.addAll(newList)
                mLastSnapshot = ArrayList(newList)
                diff.dispatchUpdatesTo(adapter)
            } else {
                mTanks.clear()
                mTanks.addAll(newList)
                mLastSnapshot = ArrayList(newList)
            }
            if (mTanks.isEmpty() && !viewModel.isLoading.value) {
                showEmpty(getString(R.string.tankoubons_empty))
            } else if (mTanks.isNotEmpty()) {
                showList()
            }
        }

        // Observe loading state. When the load finishes we must re-evaluate empty vs list
        // here, not only in the tanks collector: a server with zero tankoubons emits
        // emptyList() which StateFlow dedupes against the initial emptyList(), so the
        // tanks collector never re-runs and the spinner would otherwise spin forever.
        collectFlow(viewLifecycleOwner, viewModel.isLoading) { loading ->
            if (loading) {
                showProgress()
            } else if (mTanks.isEmpty()) {
                showEmpty(getString(R.string.tankoubons_empty))
            } else {
                showList()
            }
        }

        // Observe one-shot UI events (success/error toasts)
        collectFlow(viewLifecycleOwner, viewModel.uiEvent) { event ->
            val ctx = ehContext ?: return@collectFlow
            when (event) {
                is TankUiEvent.ShowError -> {
                    // If the list is empty, show the error view; otherwise just toast
                    if (mTanks.isEmpty()) {
                        showError(unsupportedOr(event.message))
                    } else {
                        Toast.makeText(ctx, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is TankUiEvent.ShowSuccess -> {
                    Toast.makeText(ctx, event.messageResId, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.loadTankoubons()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mProgress = null
        mErrorView = null
        mErrorIcon = null
        mErrorTitle = null
        mErrorMessage = null
        mErrorRetry = null
        mToolbar = null
        mAdapter = null
        // Reset snapshot so the next view recreation starts from a clean baseline.
        mLastSnapshot = emptyList()
    }

    // ==================== CRUD Operations ====================

    private fun showCreateDialog() {
        val ctx = ehContext ?: return
        TankDialogs.showNameInputDialog(ctx, R.string.tank_create, "") {
            viewModel.create(it)
        }
    }

    private fun showRenameDialog(tank: LRRTankoubonApi.Tankoubon) {
        val ctx = ehContext ?: return
        TankDialogs.showNameInputDialog(ctx, R.string.tank_rename, tank.name) {
            viewModel.rename(tank.id, it)
        }
    }

    private fun showEditMetaDialog(tank: LRRTankoubonApi.Tankoubon) {
        val ctx = ehContext ?: return
        TankDialogs.showMetaDialog(
            ctx,
            tank.summary.orEmpty(),
            tank.tags.orEmpty()
        ) { summary, tags ->
            viewModel.editMeta(tank.id, summary, tags)
        }
    }

    private fun showDeleteDialog(tank: LRRTankoubonApi.Tankoubon) {
        val ctx = ehContext ?: return
        TankDialogs.showDeleteConfirm(ctx) { viewModel.delete(tank.id) }
    }

    /**
     * Show long-press action menu for a tankoubon item.
     */
    private fun showTankActions(tank: LRRTankoubonApi.Tankoubon) {
        val ctx = ehContext ?: return

        val items = arrayOf(
            getString(R.string.tank_rename),
            getString(R.string.tank_edit_meta),
            getString(R.string.tank_delete)
        )

        AlertDialog.Builder(ctx)
            .setTitle(tank.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(tank)
                    1 -> showEditMetaDialog(tank)
                    2 -> showDeleteDialog(tank)
                }
            }
            .show()
    }

    // ==================== View Helpers ====================

    /**
     * Old servers lack the 0.9.8 tankoubon detail routes; once the support
     * gate has flipped (from a detail-level 404) the error surface should
     * explain the version requirement instead of a generic failure message.
     */
    private fun unsupportedOr(message: String): String {
        val serverUrl = mServerUrl
        return if (serverUrl != null && TankoubonSupportGate.isUnsupported(serverUrl)) {
            getString(R.string.tankoubons_unsupported)
        } else {
            message
        }
    }

    private fun showProgress() {
        mProgress?.visibility = View.VISIBLE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.visibility = View.GONE
    }

    private fun showList() {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.VISIBLE
        mErrorView?.visibility = View.GONE
    }

    private fun showError(message: String) {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.apply {
            visibility = View.VISIBLE
            mErrorIcon?.visibility = View.VISIBLE
            mErrorTitle?.setText(R.string.lrr_error_title)
            mErrorMessage?.apply {
                visibility = View.VISIBLE
                text = message
            }
            mErrorRetry?.apply {
                visibility = View.VISIBLE
                setOnClickListener { viewModel.loadTankoubons() }
            }
        }
    }

    private fun showEmpty(message: String) {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.apply {
            visibility = View.VISIBLE
            mErrorIcon?.visibility = View.GONE
            mErrorTitle?.text = message
            mErrorMessage?.visibility = View.GONE
            mErrorRetry?.visibility = View.GONE
        }
    }

    private fun openTankDetail(tank: LRRTankoubonApi.Tankoubon) {
        val args = Bundle().apply {
            putString(TankoubonDetailScene.KEY_TANK_ID, tank.id)
            putString(TankoubonDetailScene.KEY_TANK_NAME, tank.name)
            putLong(TankoubonDetailScene.KEY_PROFILE_ID, LRRAuthManager.getActiveProfileId())
        }
        startScene(Announcer(TankoubonDetailScene::class.java).setArgs(args))
    }

    // ==================== Adapter ====================

    private inner class TankoubonAdapter : RecyclerView.Adapter<TankoubonViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TankoubonViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tankoubon, parent, false)
            return TankoubonViewHolder(view)
        }

        override fun onBindViewHolder(holder: TankoubonViewHolder, position: Int) {
            val tank = mTanks[position]

            // Cover thumbnail (server renders a placeholder until generated)
            val serverUrl = mServerUrl
            if (serverUrl != null) {
                holder.thumb.load(
                    LRRCacheKeyFactory.getThumbKey(tank.id),
                    LRRTankoubonApi.getTankoubonThumbnailUrl(serverUrl, tank.id)
                )
            }

            holder.name.text = tank.name
            holder.count.text = getString(R.string.lrr_category_archives, tank.archives.size)

            // Global reading progress (1 = unread)
            if (tank.progress > 1) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.text = getString(R.string.tank_progress_page, tank.progress)
            } else {
                holder.progress.visibility = View.GONE
            }

            // Click to open member list
            holder.itemView.setOnClickListener { openTankDetail(tank) }

            // Long-press for actions menu
            holder.itemView.setOnLongClickListener {
                showTankActions(tank)
                true
            }
        }

        override fun getItemCount(): Int = mTanks.size
    }

    private class TankoubonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: LoadImageViewNew = itemView.findViewById(R.id.tank_thumb)
        val name: TextView = itemView.findViewById(R.id.tank_name)
        val count: TextView = itemView.findViewById(R.id.tank_count)
        val progress: TextView = itemView.findViewById(R.id.tank_progress)
    }

    /**
     * DiffUtil callback for Tankoubon lists. Identity is the tank `id`
     * (LANraragi's TANK_-prefixed string id). Content compares everything
     * the row and its dialogs read so the row (and the tank object captured
     * by its listeners) refreshes when name/member count/progress/summary/
     * tags change.
     */
    private class TankoubonDiffCallback(
        private val oldList: List<LRRTankoubonApi.Tankoubon>,
        private val newList: List<LRRTankoubonApi.Tankoubon>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.name == n.name &&
                o.archives.size == n.archives.size &&
                o.progress == n.progress &&
                o.summary == n.summary &&
                o.tags == n.tags
        }
    }
}
