package com.lanraragi.reader.ui.scene

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.ArchiveCoverStamps
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.client.LRRCacheKeyFactory
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.gallery.TankMemberSeed
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.TankPageMath
import com.lanraragi.reader.gallery.TankSeedStore
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.tankoubon.TankMemberOrderOps
import com.lanraragi.reader.tankoubon.TankMemberSelection
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.scene.TankoubonDetailViewModel.TankDetailUiEvent
import com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailsViewModel
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.reader.util.collectFlowWhileCreated
import com.lanraragi.framework.widget.LoadImageViewNew
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive
import java.util.Collections

/**
 * Detail scene for a single tankoubon: its ordered member archives plus
 * the tank-level read entries (read from start / continue at the global
 * progress page). Clicking a member opens the whole-tank session on it.
 *
 * Member management (spec 2026-09-21 §4): a long-press enters multi-select
 * (checkbox rows, count, select-all) with a floating action card — move to
 * top / bottom / position / before a picked member, reverse the selection,
 * remove, and (single selection) set as cover. Every operation is one PUT
 * of the full order through [TankoubonDetailViewModel.reorder]. Drag
 * reorder lives on the row's ≡ handle.
 *
 * Business logic (API calls, page math inputs) is delegated to
 * [TankoubonDetailViewModel]. The ViewModel is SCENE-scoped (not activity)
 * on purpose: the scene is LAUNCH_MODE_STANDARD, so two stacked detail
 * scenes must not share state.
 */
class TankoubonDetailScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mProgress: View? = null
    private var mErrorView: View? = null
    private var mErrorIcon: ImageView? = null
    private var mErrorTitle: TextView? = null
    private var mErrorMessage: TextView? = null
    private var mErrorRetry: TextView? = null
    private var mToolbar: MaterialToolbar? = null
    private var mCover: LoadImageViewNew? = null
    private var mProgressText: TextView? = null
    private var mBtnReadStart: Button? = null
    private var mBtnReadContinue: Button? = null

    private var mActionCard: View? = null
    private var mActionCount: TextView? = null
    private var mActionRowMove: View? = null
    private var mActionRowEdit: View? = null
    private var mActionCover: View? = null

    private val mMembers: MutableList<Archive> = mutableListOf()
    private var mAdapter: MemberAdapter? = null
    private var mItemTouchHelper: ItemTouchHelper? = null

    // Snapshot of the list last dispatched to the adapter. Read/written by
    // the members observer (the single dispatch path) and by the drag
    // callback's clearView (which re-syncs it after an optimistic reorder).
    private var mLastSnapshot: List<Archive> = emptyList()

    /** Multi-select state; every change re-renders the rows and the card. */
    private val selection = TankMemberSelection { onSelectionChanged() }

    /**
     * "Pick target" state of insert-before: the next row tap inserts the
     * selected block before that row instead of toggling / reading.
     */
    private var mPickingTarget = false

    /** Cover "key|url" last handed to the loader; guards duplicate loads. */
    private var mCoverBoundUrl: String? = null

    /** Page picker for "set as cover" (action card + header cover tap). */
    private var mCoverPicker: TankCoverPagePicker? = null

    private lateinit var viewModel: TankoubonDetailViewModel

    override fun getNavCheckedItem(): Int = R.id.nav_tankoubons

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_tankoubon_detail, container, false)

        val tankId = arguments?.getString(KEY_TANK_ID)
        if (tankId.isNullOrEmpty()) {
            // Defensive: every launcher passes the id. Bail out politely.
            ehContext?.let {
                Toast.makeText(it, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            }
            view.post { onBackPressed() }
            return view
        }

        // Scene-scoped on purpose — see class KDoc.
        viewModel = ViewModelProvider(this)[TankoubonDetailViewModel::class.java]
        viewModel.init(
            tankId = tankId,
            name = arguments?.getString(KEY_TANK_NAME).orEmpty(),
            profileId = arguments?.getLong(KEY_PROFILE_ID) ?: 0L,
        )

        mToolbar = view.findViewById(R.id.toolbar)
        mCover = view.findViewById(R.id.tank_cover)
        // Scene-scoped thumbnail VM: the archive detail page drives the
        // activity-scoped one and must not be reset from here.
        mCoverPicker = TankCoverPagePicker(
            view.context,
            viewLifecycleOwner,
            ViewModelProvider(this)[PageThumbnailsViewModel::class.java],
        )
        // Header cover tap: pick a page for the remembered cover member (or member #1).
        // On the frame, not the image: LoadImageViewNew resets its own click
        // listener on every load (retry-on-click).
        view.findViewById<View>(R.id.tank_cover_frame).setOnClickListener {
            val members = viewModel.members.value
            if (members.isEmpty()) return@setOnClickListener
            val remembered = viewModel.coverChoices.get(viewModel.tankId)?.arcid
            val index = members.indexOfFirst { it.arcid == remembered }.takeIf { it >= 0 } ?: 0
            pickCoverPage(index)
        }
        mProgressText = view.findViewById(R.id.tank_detail_progress)
        mBtnReadStart = view.findViewById(R.id.btn_read_start)
        mBtnReadContinue = view.findViewById(R.id.btn_read_continue)
        mProgress = view.findViewById(R.id.progress)
        mErrorView = view.findViewById(R.id.error_view)
        mErrorIcon = view.findViewById(R.id.error_icon)
        mErrorTitle = view.findViewById(R.id.error_title)
        mErrorMessage = view.findViewById(R.id.error_message)
        mErrorRetry = view.findViewById(R.id.error_retry)
        mRecyclerView = view.findViewById(R.id.recycler_view)

        setupToolbar()
        setupActionCard(view)

        mBtnReadStart?.setOnClickListener {
            if (viewModel.members.value.isNotEmpty()) {
                openTankSession(startGlobalPage = 0)
            }
        }

        mAdapter = MemberAdapter()
        mRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = mAdapter
            attachReorder(this)
        }

        observeViewModel()

        viewModel.load()

        return view
    }

    private fun setupToolbar() {
        mToolbar?.apply {
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
            inflateMenu(R.menu.scene_tankoubon_detail)
            // Edit-metadata needs server truth (summary/tags): submitting the
            // dialog's empty defaults before a successful load would WIPE
            // them. Disabled until the VM has loaded; the isLoading collector
            // keeps this fresh per load outcome.
            menu.findItem(R.id.action_tank_edit_meta)?.isEnabled = viewModel.metaLoaded
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_tank_rename -> showRenameDialog()
                    R.id.action_tank_edit_meta -> showEditMetaDialog()
                    R.id.action_tank_sort_title -> viewModel.sortByTitle()
                    R.id.action_tank_reverse -> viewModel.reverseOrder()
                    R.id.action_tank_download -> downloadTank()
                    R.id.action_tank_delete -> showDeleteDialog()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }
    }

    /** Wires the floating multi-select action card (spec 2026-09-21 §4). */
    private fun setupActionCard(root: View) {
        val card = root.findViewById<View>(R.id.member_action_card) ?: return
        mActionCard = card
        mActionCount = card.findViewById(R.id.member_action_count)
        mActionRowMove = card.findViewById(R.id.member_action_row_move)
        mActionRowEdit = card.findViewById(R.id.member_action_row_edit)
        mActionCover = card.findViewById(R.id.member_action_cover)
        card.findViewById<View>(R.id.member_action_select_all).setOnClickListener {
            selection.selectAll(mMembers.map { it.arcid })
        }
        card.findViewById<View>(R.id.member_action_top).setOnClickListener {
            applySelectionOrder { order, picked -> TankMemberOrderOps.moveToTop(order, picked) }
        }
        card.findViewById<View>(R.id.member_action_bottom).setOnClickListener {
            applySelectionOrder { order, picked -> TankMemberOrderOps.moveToBottom(order, picked) }
        }
        card.findViewById<View>(R.id.member_action_position).setOnClickListener {
            val ctx = ehContext ?: return@setOnClickListener
            if (mMembers.isEmpty()) return@setOnClickListener
            TankDialogs.showPositionDialog(ctx, mMembers.size) { position ->
                applySelectionOrder { order, picked -> TankMemberOrderOps.moveTo(order, picked, position) }
            }
        }
        card.findViewById<View>(R.id.member_action_insert_before).setOnClickListener {
            if (selection.count > 0) setPickingTarget(true)
        }
        card.findViewById<View>(R.id.member_action_reverse).setOnClickListener {
            applySelectionOrder { order, picked -> TankMemberOrderOps.reverseSelected(order, picked) }
        }
        mActionCover?.setOnClickListener {
            val only = selection.selected.singleOrNull() ?: return@setOnClickListener
            val index = viewModel.members.value.indexOfFirst { it.arcid == only }
            if (index >= 0) pickCoverPage(index) { selection.clear() }
        }
        card.findViewById<View>(R.id.member_action_remove).setOnClickListener {
            val ids = selection.selected.toList()
            if (ids.isEmpty()) return@setOnClickListener
            selection.clear()
            viewModel.removeMembers(ids)
        }
    }

    private fun observeViewModel() {
        // Toolbar title follows the tank name (nav-arg seed, then server value)
        collectFlow(viewLifecycleOwner, viewModel.tankName) { name ->
            mToolbar?.title = name
            bindCover()
        }

        // Member list
        collectFlow(viewLifecycleOwner, viewModel.members) { newList ->
            val adapter = mAdapter
            if (adapter != null) {
                val diff = DiffUtil.calculateDiff(MemberDiffCallback(mLastSnapshot, newList))
                mMembers.clear()
                mMembers.addAll(newList)
                mLastSnapshot = ArrayList(newList)
                diff.dispatchUpdatesTo(adapter)
            } else {
                mMembers.clear()
                mMembers.addAll(newList)
                mLastSnapshot = ArrayList(newList)
            }
            // A reload / removal may have dropped selected members.
            selection.retainAll(newList.map { it.arcid })
            if (mMembers.isEmpty() && !viewModel.isLoading.value) {
                showEmpty(getString(R.string.error_empty))
            } else if (mMembers.isNotEmpty()) {
                showList()
            }
            bindCover()
            bindProgressUi()
        }

        // Loading state. Re-evaluate empty vs list on finish here, not only in
        // the members collector: an empty tank emits emptyList() which the
        // StateFlow dedupes against the initial value, so the members
        // collector never re-runs and the spinner would spin forever.
        // The spinner only covers a bare scene — a reload over retained data
        // (view recreation on a retained VM) keeps the list visible; DiffUtil
        // refreshes it in place when the reload lands.
        collectFlow(viewLifecycleOwner, viewModel.isLoading) { loading ->
            // metaLoaded flips inside a successful load, right before
            // isLoading goes false — refreshing here (not in the members
            // collector) also covers the empty-tank success, whose members
            // emission is deduped against the initial emptyList().
            mToolbar?.menu?.findItem(R.id.action_tank_edit_meta)?.isEnabled =
                viewModel.metaLoaded
            if (loading) {
                if (mMembers.isEmpty()) {
                    showProgress()
                }
            } else if (mMembers.isEmpty()) {
                showEmpty(getString(R.string.error_empty))
            } else {
                showList()
            }
        }

        // Global reading progress → continue button + progress text
        collectFlow(viewLifecycleOwner, viewModel.progress) {
            bindProgressUi()
        }

        // One-shot UI events. Deleted navigates back (onBackPressed); losing it
        // in a STOPPED window while a delete completes strands the user on a
        // detail scene for a tankoubon that no longer exists. Collect for the
        // whole view lifetime (viewLifecycleOwner still cancels on view destroy).
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.uiEvent) { handleUiEvent(it) }
    }

    private fun handleUiEvent(event: TankDetailUiEvent) {
        val ctx = ehContext ?: return
        when (event) {
            is TankDetailUiEvent.ShowError -> {
                // If the list is empty, show the error view; otherwise toast
                if (mMembers.isEmpty()) {
                    showError(event.message)
                } else {
                    Toast.makeText(ctx, event.message, Toast.LENGTH_SHORT).show()
                }
            }
            TankDetailUiEvent.ShowUnsupported -> {
                // Same split as ShowError: never clobber a loaded list
                val message = getString(R.string.tankoubons_unsupported)
                if (mMembers.isEmpty()) {
                    showError(message)
                } else {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
            }
            is TankDetailUiEvent.ShowSuccess -> {
                Toast.makeText(ctx, event.messageResId, Toast.LENGTH_SHORT).show()
                // Set-cover bumps TankCoverCacheStamp, which changes the
                // cover cache key + URL — this rebind picks them up. For
                // every other op it is an idempotent no-op (same key|url).
                bindCover()
            }
            TankDetailUiEvent.Deleted -> {
                Toast.makeText(ctx, R.string.tank_op_done, Toast.LENGTH_SHORT).show()
                onBackPressed()
            }
            is TankDetailUiEvent.OrderApplied -> showUndoSnackbar(event.previousOrder)
        }
    }

    /**
     * Automatic reorder feedback (spec 2026-09-21 §3): a Snackbar whose
     * action PUTs the previous order back through the plain (non-undoable)
     * reorder path, so undoing never offers a second undo.
     */
    private fun showUndoSnackbar(previousOrder: List<String>) {
        val root = view ?: return
        Snackbar.make(root, R.string.tank_sorted, Snackbar.LENGTH_LONG)
            .setAction(R.string.tank_undo) { viewModel.reorder(previousOrder) }
            .show()
    }

    override fun onBackPressed() {
        // Back leaves "pick target", then multi-select, before leaving the scene.
        if (mPickingTarget) {
            setPickingTarget(false)
            return
        }
        if (selection.isActive) {
            selection.clear()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach so the helper releases the (dead) RecyclerView; the scene
        // object can outlive its view on the back stack.
        mItemTouchHelper?.attachToRecyclerView(null)
        mItemTouchHelper = null
        mPickingTarget = false
        mRecyclerView = null
        mProgress = null
        mErrorView = null
        mErrorIcon = null
        mErrorTitle = null
        mErrorMessage = null
        mErrorRetry = null
        mToolbar = null
        mCover = null
        mCoverPicker?.dismiss()
        mCoverPicker = null
        mProgressText = null
        mBtnReadStart = null
        mBtnReadContinue = null
        mActionCard = null
        mActionCount = null
        mActionRowMove = null
        mActionRowEdit = null
        mActionCover = null
        mAdapter = null
        // Reset snapshots so the next view recreation starts clean.
        mLastSnapshot = emptyList()
        mCoverBoundUrl = null
    }

    // ==================== Multi-select ====================

    /**
     * Applies a selection-based reorder: [op] maps the current order plus
     * the selected ids to the new order, which becomes one PUT via
     * [TankoubonDetailViewModel.reorder] (rollback on failure). The
     * selection stays active so operations can be chained.
     */
    private fun applySelectionOrder(op: (order: List<String>, selected: Set<String>) -> List<String>) {
        val picked = selection.selected
        if (picked.isEmpty()) return
        viewModel.reorder(op(viewModel.memberIds, picked))
    }

    private fun setPickingTarget(picking: Boolean) {
        if (mPickingTarget == picking) return
        mPickingTarget = picking
        renderActionCard()
    }

    private fun onSelectionChanged() {
        if (!selection.isActive) mPickingTarget = false
        renderActionCard()
        val adapter = mAdapter ?: return
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
    }

    private fun renderActionCard() {
        val card = mActionCard ?: return
        if (!selection.isActive) {
            if (card.visibility == View.VISIBLE) BatchBarAnimator.hide(card)
            return
        }
        if (card.visibility != View.VISIBLE) BatchBarAnimator.show(card)
        mActionCount?.text = if (mPickingTarget) {
            getString(R.string.tank_pick_insert_target)
        } else {
            resources.getQuantityString(R.plurals.batch_selected_count, selection.count, selection.count)
        }
        val rows = if (mPickingTarget) View.GONE else View.VISIBLE
        mActionRowMove?.visibility = rows
        mActionRowEdit?.visibility = rows
        mActionCover?.apply {
            val single = selection.count == 1
            isEnabled = single
            alpha = if (single) 1f else DISABLED_ALPHA
        }
    }

    /**
     * Row tap: in "pick target" state inserts the selected block before
     * this member; in multi-select toggles it; otherwise reads at it.
     */
    private fun onMemberClick(archive: Archive) {
        if (mPickingTarget) {
            val target = archive.arcid
            setPickingTarget(false)
            applySelectionOrder { order, picked -> TankMemberOrderOps.insertBefore(order, picked, target) }
            return
        }
        if (selection.toggle(archive.arcid)) return
        openMemberSession(archive)
    }

    // ==================== Read entries ====================

    /**
     * Binds the read-entry header (cover + read-from-start button +
     * progress text + continue-reading button) from the VM's current
     * members, global progress and page offsets. Called from both the
     * progress and members collectors — whichever lands last sees
     * complete data.
     */
    private fun bindProgressUi() {
        val p = viewModel.progress.value
        val locate = TankPageMath.locate(viewModel.pageOffsets, p)

        // Read-from-start (and the cover next to it) only make sense once
        // members are loaded — hide the dead entry on unsupported/empty tanks.
        val hasMembers = viewModel.members.value.isNotEmpty()
        mBtnReadStart?.visibility = if (hasMembers) View.VISIBLE else View.GONE
        mCover?.visibility = if (hasMembers) View.VISIBLE else View.GONE
        (mCover?.parent as? View)?.visibility = if (hasMembers) View.VISIBLE else View.GONE

        if (p > 1) {
            mProgressText?.apply {
                visibility = View.VISIBLE
                text = getString(R.string.tank_progress_page, p)
            }
        } else {
            mProgressText?.visibility = View.GONE
        }

        val btn = mBtnReadContinue ?: return
        if (p > 1 && locate != null) {
            btn.visibility = View.VISIBLE
            btn.text = getString(R.string.tank_continue_reading, p)
            // Whole-tank session at the server's GLOBAL progress (0-indexed).
            btn.setOnClickListener { openTankSession(startGlobalPage = p - 1) }
        } else {
            btn.visibility = View.GONE
        }
    }

    /**
     * Launch the WHOLE-TANK composite reader session (spec
     * 2026-08-05-tank-seamless-reading): every member rides one continuous
     * page space, so both read entries land in the same session kind.
     * [startGlobalPage] -1 = provider-restored progress.
     */
    private fun openTankSession(startGlobalPage: Int) {
        val ctx = ehContext ?: return
        val seed = buildSessionSeed() ?: return
        TankSeedStore.publish(seed)
        startActivity(GalleryOpenHelper.buildTankReadIntent(ctx, seed, startGlobalPage))
    }

    private fun buildSessionSeed(): TankSessionSeed? {
        val members = viewModel.members.value
        if (members.isEmpty()) return null
        return TankSessionSeed(
            tankId = viewModel.tankId,
            tankName = viewModel.tankName.value,
            profileId = viewModel.profileId,
            members = members.map { TankMemberSeed(it.arcid, it.title, it.pagecount) },
        )
    }

    /**
     * Member row click (spec 2026-09-21 §5): members have no standalone
     * detail page — the row opens the WHOLE-TANK session positioned on that
     * member (saved tank progress inside it restores, otherwise its first
     * page).
     */
    private fun openMemberSession(archive: Archive) {
        val ctx = ehContext ?: return
        val seed = buildSessionSeed() ?: return
        val members = viewModel.members.value
        val start = TankPageMath.anchoredStart(
            members.map { it.pagecount },
            members.indexOfFirst { it.arcid == archive.arcid },
            GalleryProvider2.loadReadingProgress(ctx, viewModel.tankId),
        )
        TankSeedStore.publish(seed)
        startActivity(GalleryOpenHelper.buildTankReadIntent(ctx, seed, start))
    }

    // ==================== Management ops ====================

    private fun showRenameDialog() {
        val ctx = ehContext ?: return
        TankDialogs.showNameInputDialog(ctx, R.string.tank_rename, viewModel.tankName.value) {
            viewModel.rename(it)
        }
    }

    private fun showEditMetaDialog() {
        val ctx = ehContext ?: return
        TankDialogs.showMetaDialog(
            ctx,
            viewModel.summary.orEmpty(),
            viewModel.tags.orEmpty()
        ) { summary, tags ->
            viewModel.editMeta(summary, tags)
        }
    }

    /**
     * Download the whole tank: [TankFillDispatcher] is the shared fill path
     * (also behind the downloads card start control and the drawer
     * long-press), so worker / notification behavior matches ordinary
     * downloads and the group row is re-tagged with full membership.
     */
    private fun downloadTank() {
        val ctx = ehContext ?: return
        val members = viewModel.members.value
        if (members.isEmpty()) {
            Toast.makeText(ctx, R.string.error_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dm = ServiceRegistry.dataModule.downloadManager
        val plan = TankFillDispatcher.plan(members) { dm.getDownloadState(it) }
        TankFillDispatcher.dispatch(
            ctx, dm, plan,
            viewModel.tankId, viewModel.tankName.value, viewModel.profileId,
            members.map { it.arcid },
        )
        Toast.makeText(ctx, TankFillDispatcher.feedback(resources, plan), Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteDialog() {
        val ctx = ehContext ?: return
        TankDialogs.showDeleteConfirm(ctx) { viewModel.deleteTank() }
    }

    /**
     * Drag-to-reorder from the row's ≡ handle (spec 2026-09-21 §4): the
     * handle's touch-down starts the drag ([ItemTouchHelper.startDrag]);
     * long-press drag is off because a row long-press now enters
     * multi-select. Release with a moved row PUTs the new order.
     */
    private fun attachReorder(recycler: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            /** Whether the current drag selection actually swapped rows. */
            private var dragged = false

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }
                // Optimistic UI: swap the scene's copy only; the VM
                // reconciles authoritative state in reorder() on release.
                Collections.swap(mMembers, from, to)
                mAdapter?.notifyItemMoved(from, to)
                dragged = true
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (!dragged) return
                dragged = false
                // Keep the local snapshot in sync so the members
                // collector's next DiffUtil pass (the VM re-emission in
                // the SAME order) is a no-op instead of a bounce-back.
                mLastSnapshot = ArrayList(mMembers)
                viewModel.reorder(mMembers.map { it.arcid })
            }
        }
        mItemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recycler) }
    }

    // ==================== View helpers ====================

    /**
     * Loads the tank cover once the VM has resolved the source base URL
     * (first load). Idempotent per key|url so the multiple observer call
     * sites do not restart the image load.
     *
     * The image pipeline caches by KEY, not URL — BOTH carry the
     * process-wide [TankCoverCacheStamp] (bumped by every successful tank
     * fetch and by set-cover), otherwise a cover regenerated server-side
     * would be shadowed by the stale cached image forever. (The pre-bump
     * image stays cached under the old key until evicted — accepted.)
     */
    override fun onResume() {
        super.onResume()
        // The reader may have set a new cover (TankCoverCacheStamp bumped);
        // bindCover short-circuits when the key/url binding is unchanged.
        bindCover()
    }

    /**
     * Opens the page picker for member [memberIndex]; the picked page (or
     * page 1 via the shortcut) becomes the cover. [afterPick] runs once a
     * page was chosen (not on dismiss).
     */
    private fun pickCoverPage(memberIndex: Int, afterPick: () -> Unit = {}) {
        val member = viewModel.members.value.getOrNull(memberIndex) ?: return
        mCoverPicker?.show(member) { page0 ->
            viewModel.setCover(memberIndex, page0)
            afterPick()
        }
    }

    private fun bindCover() {
        val baseUrl = viewModel.baseUrl ?: return
        val tankId = viewModel.tankId
        if (tankId.isEmpty()) return
        val key: String
        val url: String
        val fallback = viewModel.coverFallbackMember
        if (fallback != null) {
            // No generated cover server-side (probe 202, generation queued):
            // stand in with the first member's cover.
            key = LRRCacheKeyFactory.getThumbKey(fallback.arcid)
            url = ArchiveCoverStamps.bust(fallback.thumbnailUrl, fallback.arcid)
        } else {
            val bust = TankCoverCacheStamp.value
            key = LRRCacheKeyFactory.getThumbKey("$tankId#$bust")
            url = LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, tankId, cacheBust = bust)
        }
        val binding = "$key|$url"
        if (binding == mCoverBoundUrl) return
        mCoverBoundUrl = binding
        mCover?.load(key, url)
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
                setOnClickListener { viewModel.load() }
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

    // ==================== Adapter ====================

    private inner class MemberAdapter : RecyclerView.Adapter<MemberViewHolder>() {

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tankoubon_member, parent, false)
            val holder = MemberViewHolder(view)
            // Long-press = enter multi-select and check this row (touch and
            // accessibility long-click alike). Drags start from the handle.
            holder.itemView.setOnLongClickListener {
                if (mPickingTarget) return@setOnLongClickListener false
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val archive = mMembers.getOrNull(position) ?: return@setOnLongClickListener false
                selection.enterAndToggle(archive.arcid)
                true
            }
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !selection.isActive) {
                    mItemTouchHelper?.startDrag(holder)
                }
                false
            }
            return holder
        }

        // SetTextI18n: the trailing "P" unit suffix mirrors the page badge in
        // GalleryAdapterNew ("12/34P") and is deliberately not localized.
        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            val a = mMembers[position]

            holder.thumb.load(LRRCacheKeyFactory.getThumbKey(a.arcid), ArchiveCoverStamps.bust(a.thumbnailUrl, a.arcid))
            holder.title.text = a.title

            if (a.pagecount > 0) {
                holder.pages.visibility = View.VISIBLE
                holder.pages.text = "${a.pagecount}P"
            } else {
                holder.pages.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onMemberClick(a) }
            bindSelection(holder, a)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_SELECTION)) {
                bindSelection(holder, mMembers[position])
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        /** Checkbox / handle state only — the cheap part of a bind. */
        private fun bindSelection(holder: MemberViewHolder, a: Archive) {
            val selecting = selection.isActive
            holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.check.isChecked = selecting && selection.isSelected(a.arcid)
            holder.handle.visibility = if (selecting) View.GONE else View.VISIBLE
        }

        override fun getItemCount(): Int = mMembers.size
    }

    private class MemberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val check: CheckBox = itemView.findViewById(R.id.member_check)
        val thumb: LoadImageViewNew = itemView.findViewById(R.id.member_thumb)
        val title: TextView = itemView.findViewById(R.id.member_title)
        val pages: TextView = itemView.findViewById(R.id.member_pages)
        val handle: View = itemView.findViewById(R.id.member_drag_handle)
    }

    /**
     * DiffUtil callback for member lists. Identity is the arcid; contents
     * compare what the row renders (title / pagecount / thumbnailUrl).
     */
    private class MemberDiffCallback(
        private val oldList: List<Archive>,
        private val newList: List<Archive>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].arcid == newList[newItemPosition].arcid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.title == n.title &&
                o.pagecount == n.pagecount &&
                o.thumbnailUrl == n.thumbnailUrl
        }
    }

    companion object {
        const val KEY_TANK_ID = "tank_id"
        const val KEY_TANK_NAME = "tank_name"
        const val KEY_PROFILE_ID = "tank_profile_id"

        private const val PAYLOAD_SELECTION = "selection"
        private const val DISABLED_ALPHA = 0.38f
    }
}
