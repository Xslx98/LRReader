/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.history

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.swipeable.RecyclerViewSwipeManager
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemConstants
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultAction
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionDefault
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionRemoveItem
import com.h6ah4i.android.widget.advrecyclerview.touchguard.RecyclerViewTouchActionGuardManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractSwipeableItemViewHolder
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.EnterGalleryDetailTransaction
import com.hippo.ehviewer.widget.SimpleRatingView
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.widget.LoadImageView
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils

class HistoryScene : ToolbarScene(),
    EasyRecyclerView.OnItemClickListener,
    EasyRecyclerView.OnItemLongClickListener {

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var mLazyList: List<HistoryInfo>? = null
    private var mLayoutManager: AutoStaggeredGridLayoutManager? = null

    // Snapshot of the list last dispatched to the adapter. Read/written ONLY by
    // updateLazyList() (the single dispatch path). Used to compute DiffUtil deltas
    // against the freshly loaded list. See docs/diffutil-root-cause-analysis.md
    // for why we are careful about snapshot ownership.
    private var mLastSnapshot: List<HistoryInfo> = emptyList()

    override fun getNavCheckedItem(): Int {
        return R.id.nav_history
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_history, container, false)
        val content = ViewUtils.`$$`(view, R.id.content)
        mRecyclerView = ViewUtils.`$$`(content, R.id.recycler_view) as EasyRecyclerView
        val fastScroller = ViewUtils.`$$`(content, R.id.fast_scroller) as FastScroller
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        mViewTransition = ViewTransition(content, tip)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        val resources = context!!.resources

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_history)
        drawable!!.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)

        val guardManager = RecyclerViewTouchActionGuardManager()
        guardManager.setInterceptVerticalScrollingWhileAnimationRunning(true)
        guardManager.isEnabled = true
        val swipeManager = RecyclerViewSwipeManager()
        var adapter: RecyclerView.Adapter<*> = HistoryAdapter()
        adapter.setHasStableIds(true)
        adapter = swipeManager.createWrappedAdapter(adapter)
        mAdapter = adapter
        mRecyclerView!!.adapter = mAdapter
        val animator = SwipeDismissItemAnimator()
        animator.supportsChangeAnimations = false
        mRecyclerView!!.itemAnimator = animator
        mLayoutManager = AutoStaggeredGridLayoutManager(
            0, StaggeredGridLayoutManager.VERTICAL
        )
        mLayoutManager!!.setColumnSize(
            resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
        )
        mLayoutManager!!.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)
        mRecyclerView!!.layoutManager = mLayoutManager
        mRecyclerView!!.setSelector(
            Ripple.generateRippleDrawable(
                context,
                !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
                ColorDrawable(Color.TRANSPARENT)
            )
        )
        mRecyclerView!!.setDrawSelectorOnTop(true)
        mRecyclerView!!.clipToPadding = false
        mRecyclerView!!.setOnItemClickListener(this)
        mRecyclerView!!.setOnItemLongClickListener(this)
        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        mRecyclerView!!.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)
        guardManager.attachRecyclerView(mRecyclerView!!)
        swipeManager.attachRecyclerView(mRecyclerView!!)

        fastScroller.attachToRecyclerView(mRecyclerView)
        val handlerDrawable = HandlerDrawable()
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)

        updateLazyList()
        updateView(false)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.history)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    override fun onResume() {
        super.onResume()
        // Refresh column size to pick up detail_size changes from settings
        if (mLayoutManager != null) {
            val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
            mLayoutManager!!.setColumnSize(columnWidth)
            mRecyclerView?.requestLayout()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (mLazyList != null) {
            mLazyList = null
            // Adapter is being torn down with this view; a structural notify is
            // required so the framework drops cached ViewHolders that referenced
            // mLazyList. notifyDataSetChanged is acceptable here because there is
            // no concurrent dispatch path during view destruction.
            @Suppress("NotifyDataSetChanged")
            mAdapter?.notifyDataSetChanged()
        }
        // Reset snapshot so the next onCreateView starts from an empty baseline
        // and the first updateLazyList() dispatch is a clean inserts-only delta.
        mLastSnapshot = emptyList()
        if (mRecyclerView != null) {
            mRecyclerView!!.stopScroll()
            mRecyclerView = null
        }

        mViewTransition = null
        mAdapter = null
    }

    // Asynchronously loads history list on IO thread, then updates UI.
    // Uses DiffUtil for the dispatch. SwipeResultActionClear (swipe-to-dismiss)
    // calls into this same path after async DB delete completes; the diff-driven
    // remove of the swiped item should align with the SwipeDismissItemAnimator's
    // ongoing animation since we keep mLastSnapshot in sync with what the adapter
    // last saw. setHasStableIds(true) (line 114) helps the animator track items.
    private fun updateLazyList() {
        lifecycleScope.launch {
            val lazyList = withContext(Dispatchers.IO) { EhDB.getHistoryLazyListAsync() }
            val adapter = mAdapter
            val newList = ArrayList(lazyList)
            if (adapter != null) {
                val diff = DiffUtil.calculateDiff(
                    HistoryInfoDiffCallback(mLastSnapshot, newList)
                )
                mLazyList = newList
                mLastSnapshot = newList
                diff.dispatchUpdatesTo(adapter)
            } else {
                mLazyList = newList
                mLastSnapshot = newList
            }
            updateView(false)
        }
    }

    /**
     * DiffUtil callback for HistoryInfo lists. Identity is `gid` (Room PK).
     * Content compares all fields rendered in onBindViewHolder so a metadata
     * refresh repaints the affected rows.
     */
    private class HistoryInfoDiffCallback(
        private val oldList: List<HistoryInfo>,
        private val newList: List<HistoryInfo>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].gid == newList[newItemPosition].gid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.title == n.title &&
                o.uploader == n.uploader &&
                o.rating == n.rating &&
                o.category == n.category &&
                o.posted == n.posted &&
                o.simpleLanguage == n.simpleLanguage &&
                o.thumb == n.thumb
        }
    }

    private fun updateView(animation: Boolean) {
        if (mAdapter == null || mViewTransition == null) {
            return
        }

        if (mAdapter!!.itemCount == 0) {
            mViewTransition!!.showView(1, animation)
        } else {
            mViewTransition!!.showView(0, animation)
        }
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int {
        return R.menu.scene_history
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(ehContext!!)
            .setMessage(R.string.clear_all_history)
            .setPositiveButton(R.string.clear_all) { dialog, which ->
                if (DialogInterface.BUTTON_POSITIVE != which || mAdapter == null) {
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { EhDB.clearHistoryInfoAsync() }
                    updateLazyList()
                }
            }.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        // Skip when in choice mode
        val context = ehContext ?: return false

        val id = item.itemId
        when (id) {
            R.id.action_clear_all -> {
                showClearAllDialog()
                return true
            }
        }
        return false
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val lazyList = mLazyList ?: return false

        val args = Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO)
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, lazyList[position])
        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
        val thumb = view.findViewById<View>(R.id.thumb)
        if (thumb != null) {
            announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
        }
        startScene(announcer)
        return true
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val context = ehContext ?: return false
        val activity = activity2 ?: return false
        val lazyList = mLazyList ?: return false

        val gi = lazyList[position]
        AlertDialog.Builder(context)
            .setTitle(EhUtils.getSuitableTitle(gi))
            .setItems(R.array.gallery_list_menu_entries) { _, which ->
                when (which) {
                    0 -> // Download
                        CommonOperations.startDownload(activity, gi, false)
                    1 -> // Favorites
                        CommonOperations.addToFavorites(
                            activity, gi,
                            AddToFavoriteListener(
                                context,
                                activity.stageId, tag
                            ), false
                        )
                }
            }.show()
        return true
    }

    private inner class HistoryHolder(itemView: View) : AbstractSwipeableItemViewHolder(itemView) {

        val card: View = itemView.findViewById(R.id.card)
        val thumb: LoadImageView = itemView.findViewById(R.id.thumb)
        val title: TextView = itemView.findViewById(R.id.title)
        val uploader: TextView = itemView.findViewById(R.id.uploader)
        val rating: SimpleRatingView = itemView.findViewById(R.id.rating)
        val category: TextView = itemView.findViewById(R.id.category)
        val posted: TextView = itemView.findViewById(R.id.posted)
        val simpleLanguage: TextView = itemView.findViewById(R.id.simple_language)

        override fun getSwipeableContainerView(): View {
            return card
        }
    }

    private inner class HistoryAdapter :
        RecyclerView.Adapter<HistoryHolder>(),
        SwipeableItemAdapter<HistoryHolder> {

        private val mInflater: LayoutInflater = layoutInflater2
        private val mListThumbWidth: Int
        private val mListThumbHeight: Int

        init {
            val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
            ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
            mListThumbHeight = calculator.measuredHeight
            mListThumbWidth = mListThumbHeight * 2 / 3
        }

        override fun getItemId(position: Int): Long {
            return mLazyList?.get(position)?.gid ?: super.getItemId(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val holder = HistoryHolder(mInflater.inflate(R.layout.item_history, parent, false))

            val lp = holder.thumb.layoutParams
            lp.width = mListThumbWidth
            lp.height = mListThumbHeight
            holder.thumb.layoutParams = lp

            return holder
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val lazyList = mLazyList ?: return

            val gi = lazyList[position]
            holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
            holder.title.text = EhUtils.getSuitableTitle(gi)
            holder.uploader.text = gi.uploader
            holder.rating.setRating(gi.rating)
            val category = holder.category
            val newCategoryText = EhUtils.getCategory(gi.category)
            if (newCategoryText != category.text.toString()) {
                category.text = newCategoryText
                category.setBackgroundColor(EhUtils.getCategoryColor(gi.category))
            }
            holder.posted.text = gi.posted
            holder.simpleLanguage.text = gi.simpleLanguage

            // Update transition name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val gid = gi.gid
                ViewCompat.setTransitionName(
                    holder.thumb,
                    TransitionNameFactory.getThumbTransitionName(gid)
                )
            }
        }

        override fun getItemCount(): Int {
            return mLazyList?.size ?: 0
        }

        override fun onGetSwipeReactionType(holder: HistoryHolder, position: Int, x: Int, y: Int): Int {
            return SwipeableItemConstants.REACTION_CAN_SWIPE_LEFT
        }

        override fun onSwipeItemStarted(holder: HistoryHolder, position: Int) {}

        override fun onSetSwipeBackground(holder: HistoryHolder, position: Int, type: Int) {}

        override fun onSwipeItem(holder: HistoryHolder, position: Int, result: Int): SwipeResultAction {
            return when (result) {
                SwipeableItemConstants.RESULT_SWIPED_LEFT -> SwipeResultActionClear(position)
                else -> SwipeResultActionDefault()
            }
        }
    }

    private inner class SwipeResultActionClear(
        private val mPosition: Int
    ) : SwipeResultActionRemoveItem() {

        override fun onPerformAction() {
            super.onPerformAction()
            val lazyList = mLazyList ?: return
            if (mAdapter == null) return

            val info = lazyList[mPosition]
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { EhDB.deleteHistoryInfoAsync(info) }
                updateLazyList()
            }
        }
    }

    private class AddToFavoriteListener(
        context: android.content.Context,
        stageId: Int,
        sceneTag: String?
    ) : EhCallback<HistoryScene, Void?>(context, stageId, sceneTag) {

        override fun onSuccess(result: Void?) {
            showTip(R.string.add_to_favorite_success, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.add_to_favorite_failure, LENGTH_LONG)
        }

        override fun onCancel() {}

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is HistoryScene
        }
    }
}
