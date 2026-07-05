package com.hippo.ehviewer.ui.scene

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.gallery.TankPageMath
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.ehviewer.ui.scene.TankoubonDetailViewModel.TankDetailUiEvent
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.util.collectFlow
import com.hippo.scene.Announcer
import com.hippo.widget.LoadImageViewNew
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail scene for a single tankoubon: its ordered member archives plus
 * the tank-level read entries (read from start / continue at the global
 * progress page). Clicking a member opens its [GalleryDetailScene].
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

    private val mMembers: MutableList<Archive> = mutableListOf()
    private var mAdapter: MemberAdapter? = null

    // Snapshot of the list last dispatched to the adapter. Read/written ONLY
    // by the members observer (the single dispatch path).
    private var mLastSnapshot: List<Archive> = emptyList()

    /** Cover URL last handed to the loader; guards duplicate load() calls. */
    private var mCoverBoundUrl: String? = null

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

        mToolbar?.apply {
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
        }

        mBtnReadStart?.setOnClickListener {
            if (viewModel.members.value.isNotEmpty()) {
                openMemberAtPage(memberIndex = 0, page0 = 0)
            }
        }

        mAdapter = MemberAdapter()
        mRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = mAdapter
        }

        observeViewModel()

        viewModel.load()

        return view
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

        // One-shot UI events
        collectFlow(viewLifecycleOwner, viewModel.uiEvent) { event ->
            val ctx = ehContext ?: return@collectFlow
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
                is TankDetailUiEvent.ShowSuccess -> Unit // Task 8
                TankDetailUiEvent.Deleted -> Unit // Task 8
            }
        }
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
        mCover = null
        mProgressText = null
        mBtnReadStart = null
        mBtnReadContinue = null
        mAdapter = null
        // Reset snapshots so the next view recreation starts clean.
        mLastSnapshot = emptyList()
        mCoverBoundUrl = null
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
            val (memberIndex, page0) = locate
            btn.visibility = View.VISIBLE
            btn.text = getString(R.string.tank_continue_reading, p)
            btn.setOnClickListener { openMemberAtPage(memberIndex, page0) }
        } else {
            btn.visibility = View.GONE
        }
    }

    /**
     * Open the reader on member [memberIndex] at local 0-indexed [page0].
     * Reuses [GalleryOpenHelper] so local/stream routing and warmup stay
     * identical to the regular detail-page "Read" button.
     */
    private fun openMemberAtPage(memberIndex: Int, page0: Int) {
        val archive = viewModel.members.value.getOrNull(memberIndex) ?: return
        val ctx = ehContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(
            ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val intent = withContext(Dispatchers.IO) {
                GalleryOpenHelper.buildReadIntent(ctx, archive, startPage = page0)
            }
            startActivity(intent)
        }
    }

    private fun openMemberDetail(archive: Archive) {
        val args = Bundle().apply {
            putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_ARCHIVE)
            putParcelable(GalleryDetailScene.KEY_ARCHIVE, archive)
        }
        startScene(Announcer(GalleryDetailScene::class.java).setArgs(args))
    }

    // ==================== View helpers ====================

    /**
     * Loads the tank cover once the VM has resolved the source base URL
     * (first load). Idempotent per URL so the multiple observer call sites
     * do not restart the image load.
     */
    private fun bindCover() {
        val baseUrl = viewModel.baseUrl ?: return
        val tankId = viewModel.tankId
        if (tankId.isEmpty()) return
        val url = LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, tankId)
        if (url == mCoverBoundUrl) return
        mCoverBoundUrl = url
        mCover?.load(LRRCacheKeyFactory.getThumbKey(tankId), url)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tankoubon_member, parent, false)
            return MemberViewHolder(view)
        }

        // SetTextI18n: the trailing "P" unit suffix mirrors the page badge in
        // GalleryAdapterNew ("12/34P") and is deliberately not localized.
        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            val a = mMembers[position]

            holder.thumb.load(LRRCacheKeyFactory.getThumbKey(a.arcid), a.thumbnailUrl)
            holder.title.text = a.title

            if (a.pagecount > 0) {
                holder.pages.visibility = View.VISIBLE
                holder.pages.text = "${a.pagecount}P"
            } else {
                holder.pages.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { openMemberDetail(a) }
        }

        override fun getItemCount(): Int = mMembers.size
    }

    private class MemberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: LoadImageViewNew = itemView.findViewById(R.id.member_thumb)
        val title: TextView = itemView.findViewById(R.id.member_title)
        val pages: TextView = itemView.findViewById(R.id.member_pages)
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
    }
}
