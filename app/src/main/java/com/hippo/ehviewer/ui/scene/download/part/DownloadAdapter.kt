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
package com.hippo.ehviewer.ui.scene.download.part

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.mapper.toArchive
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.gallery.Pipe
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.EnterGalleryDetailTransaction
import com.hippo.ehviewer.widget.SimpleRatingView
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import com.hippo.widget.LoadImageView
import kotlinx.coroutines.launch
import com.hippo.ehviewer.download.DownloadState
import com.lanraragi.reader.client.api.isTankoubonId
import java.util.concurrent.CompletableFuture

/**
 * 下载列表适配器
 */
// InflateParams: the init-block calculator view is measure-only, never attached.
@SuppressLint("InflateParams")
class DownloadAdapter(
    private val mScene: DownloadsScene,
    private val mCallback: DownloadAdapterCallback
) : RecyclerView.Adapter<DownloadAdapter.DownloadHolder>(),
    DraggableItemAdapter<DownloadAdapter.DownloadHolder> {

    private val mInflater: LayoutInflater
    private val mListThumbWidth: Int
    private val mListThumbHeight: Int

    private var movedItem: View? = null


    interface DownloadAdapterCallback {
        val indexPage: Int
        val pageSize: Int
        val paginationSize: Int
        val isCanPagination: Boolean
        fun positionInList(position: Int): Int
        fun listIndexInPage(position: Int): Int
        val list: List<DownloadInfo>?
        val spiderInfoMap: Map<String, SpiderInfo>
        val downloadManager: DownloadManager?
        val recyclerView: EasyRecyclerView?

        /**
         * VM-cached download-dir resolution for [info]'s thumbnail, shared
         * across rebinds of the same archive (DL-12). See
         * [com.hippo.ehviewer.ui.scene.download.DownloadsViewModel.downloadDirFutureFor].
         */
        fun downloadDirFutureFor(info: DownloadInfo): CompletableFuture<UniFile?>

        /** Aggregate (finished, total) member pages behind a tank card (Track 2). */
        fun tankProgressFor(tankId: String): Pair<Int, Int>
    }

    init {
        DRAG_ENABLE = DownloadSettings.getDragDownloadGallery()

        val inflater = try {
            mScene.layoutInflater2
        } catch (e: NullPointerException) {
            fallbackInflater()
        } catch (e: IllegalStateException) {
            fallbackInflater()
        }
        mInflater = inflater

        val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
        mListThumbHeight = calculator.measuredHeight
        mListThumbWidth = mListThumbHeight * 2 / 3
    }

    private fun fallbackInflater(): LayoutInflater {
        val context = mScene.context
        if (context != null) {
            return LayoutInflater.from(context)
        }
        val activity = mScene.activity
        if (activity != null) {
            return LayoutInflater.from(activity)
        }
        throw IllegalStateException("Cannot get LayoutInflater: Fragment is not attached and Context/Activity is null")
    }

    override fun getItemId(position: Int): Long {
        val posInList = mCallback.positionInList(position)
        val list = mCallback.list ?: return 0
        if (posInList < 0 || posInList >= list.size) {
            return 0
        }
        return list[posInList].arcid.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadHolder {
        val holder = DownloadHolder(mInflater.inflate(R.layout.item_download, parent, false))
        val lp = holder.thumb.layoutParams
        lp.width = mListThumbWidth
        lp.height = mListThumbHeight
        holder.thumb.layoutParams = lp
        return holder
    }

    /**
     * Payload-aware bind. When the payload list contains
     * [DownloadsScene.PAYLOAD_PROGRESS], only the progress / speed / percent
     * views are refreshed — no image reload, no tag rebuild. This path is
     * exercised on every progress-tracker tick via
     * `DownloadsScene.dispatchProgressChanges`.
     */
    override fun onBindViewHolder(
        holder: DownloadHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() &&
            payloads.any { it == DownloadsScene.PAYLOAD_PROGRESS }
        ) {
            val list = mCallback.list ?: return
            val pos = mCallback.positionInList(position)
            if (pos !in list.indices) return
            val info = list[pos]
            if (info.state == DownloadState.DOWNLOAD ||
                info.state == DownloadState.WAIT
            ) {
                // Same row ticking: animate the sub-page advance (80 ms
                // system ease between the tracker's 2 s ticks).
                bindProgress(holder, info, animate = true)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: DownloadHolder, position: Int) {
        val list = mCallback.list ?: return

        try {
            val pos = mCallback.positionInList(position)
            val info = list[pos]
            val archive = info.toArchive()

            holder.thumb.load(
                LRRCacheKeyFactory.getThumbKey(info.arcid), archive.thumbnailUrl,
                ThumbDataContainer(mCallback.downloadDirFutureFor(info)), true, false
            )

            holder.title.text = archive.title
            holder.uploader.visibility = View.GONE

            if (archive.rating <= 0) {
                holder.rating.visibility = View.GONE
            } else {
                holder.rating.setRating(archive.rating)
            }

            val spiderInfo = info.arcid?.let { mCallback.spiderInfoMap[it] }
            if (spiderInfo != null) {
                val startPage = spiderInfo.startPage + 1
                val readText = "$startPage/${spiderInfo.pages}"
                holder.readProgress.text = readText
            }

            holder.category.visibility = View.GONE

            bindSourceBadge(holder, info)
            bindForState(holder, info)

            ViewCompat.setTransitionName(holder.thumb, TransitionNameFactory.getThumbTransitionName(info.arcid))
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    override fun getItemCount(): Int {
        val list = mCallback.list ?: return 0
        val listSize = list.size
        if (listSize < mCallback.paginationSize || !mCallback.isCanPagination) {
            return listSize
        }
        val count = listSize - mCallback.pageSize * (mCallback.indexPage - 1)
        return count.coerceAtMost(mCallback.pageSize)
    }

    /**
     * Render the source-server badge on a download row. Always visible —
     * uniform layout regardless of which profile the row originates from.
     *
     * Color scheme follows Material 3 "container / on-container" tonal
     * pairs (also: GitHub Issues labels, Linear project tags): each
     * profile-id slot has BG (`?attr/serverBadge{N}Bg`) + FG
     * (`?attr/serverBadge{N}Fg`) channels resolved from the current theme,
     * so the badge surface harmonises with the surrounding card surface
     * (light tint on light card, dark shade on dark card).
     *
     *  - Active profile or non-active profile (id resolves): square chip
     *    showing `profile.name` with theme-paired bg + fg colors.
     *  - Legacy row (`serverProfileId == 0`, pre-SERVER_PROFILE_ID
     *    schema): treat as belonging to the active profile and show its
     *    name + color. Most legacy rows pre-date the multi-profile era
     *    when only one profile existed; this is the closest faithful
     *    attribution we can make. If no active profile exists, falls
     *    through to the orphan path below.
     *  - Orphan (id non-zero but cache miss): square chip with
     *    `?attr/colorError` tint, white fg, and the localised "source
     *    deleted" label. Distinct from the regular palette so it reads
     *    as warning state across all themes.
     *
     * Resolution goes through
     * [com.lanraragi.reader.client.api.ProfileLookupCache] which tracks
     * SERVER_PROFILES via Room flow; profile rename / delete is picked
     * up on the next bind because the cache snapshot is shared.
     */
    private fun bindSourceBadge(holder: DownloadHolder, info: DownloadInfo) {
        val badge = holder.sourceServer
        val context = mScene.ehContext ?: return

        // Visual binding (text + colour pair + drawable tint) is shared
        // with the detail-header badge so both surfaces show identical
        // labels / colours for the same profile.
        com.hippo.ehviewer.ui.widget.bindSourceServerBadge(badge, info.serverProfileId)

        // The XML pins source_server 8dp above thumb's bottom — fine when
        // category is GONE (the LRR-archive default). For imported archives
        // category becomes VISIBLE at the same alignBottom anchor; lift the
        // badge ~30dp so they don't overlap. Niche branch — keep the XML
        // optimised for the main case and pay the runtime cost only when
        // category is actually shown.
        val params = badge.layoutParams as RelativeLayout.LayoutParams
        val density = context.resources.displayMetrics.density
        val margin = if (holder.category.isVisible) {
            (BADGE_MARGIN_BOTTOM_WHEN_CATEGORY_VISIBLE_DP * density).toInt()
        } else {
            (BADGE_MARGIN_BOTTOM_DEFAULT_DP * density).toInt()
        }
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            badge.layoutParams = params
        }
        // bindSourceServerBadge already set visibility = VISIBLE; this
        // re-assertion is a defence against a previous bind having
        // hidden the view (e.g. category-switching codepaths upstream).
        badge.visibility = View.VISIBLE
    }

    private fun bindForState(holder: DownloadHolder, info: DownloadInfo) {
        val resources = mScene.resources2 ?: return

        // Tank cards (synthetic TANK_ rows, Track 2) render as a single
        // aggregate: state text + member-page tally, no per-row
        // start/stop (the card is not one schedulable download).
        if (isTankoubonId(info.arcid)) {
            bindTankCard(holder, info, resources)
            return
        }

        when (info.state) {
            DownloadState.INVALID,
            DownloadState.NONE -> bindState(holder, info, resources.getString(R.string.download_state_none))
            DownloadState.WAIT -> bindState(holder, info, resources.getString(R.string.download_state_wait))
            // Full (re)bind snaps without animation: a recycled holder may
            // still carry another row's progress value and would sweep from
            // a wrong origin.
            DownloadState.DOWNLOAD -> bindProgress(holder, info, animate = false)
            DownloadState.FAILED -> {
                val text = if (info.legacy <= 0) {
                    resources.getString(R.string.download_state_failed)
                } else {
                    resources.getQuantityString(
                        R.plurals.download_state_failed_2, info.legacy, info.legacy
                    )
                }
                bindState(holder, info, text)
            }
            DownloadState.FINISH -> bindState(holder, info, resources.getString(R.string.download_state_finish))
        }
    }

    private fun setVisibility(view: View, visibility: Int) {
        if (view.visibility != visibility) view.visibility = visibility
    }

    /**
     * Apply the title line budget for the current row state. Guarded like
     * [setVisibility] so the ~0.5 Hz progress re-binds don't request a layout
     * when the value is unchanged.
     */
    private fun applyTitleLines(holder: DownloadHolder, showingProgress: Boolean) {
        val lines = downloadTitleMaxLines(showingProgress)
        if (holder.title.maxLines != lines) holder.title.maxLines = lines
    }

    @SuppressLint("SetTextI18n")
    private fun bindTankCard(
        holder: DownloadHolder,
        info: DownloadInfo,
        resources: android.content.res.Resources,
    ) {
        cancelProgressGlide(holder)
        applyTitleLines(holder, showingProgress = false)
        // No spider info exists for a TANK_ id — clear whatever a recycled
        // holder carried instead of showing another row's read progress.
        holder.readProgress.text = null
        setVisibility(holder.uploader, View.GONE)
        setVisibility(holder.state, View.VISIBLE)
        setVisibility(holder.progressBar, View.GONE)
        setVisibility(holder.percent, View.GONE)
        setVisibility(holder.speed, View.GONE)
        setVisibility(holder.start, View.GONE)
        setVisibility(holder.stop, View.GONE)
        val base = when (info.state) {
            DownloadState.WAIT,
            DownloadState.DOWNLOAD -> resources.getString(R.string.download_state_downloading)
            DownloadState.FAILED -> resources.getString(R.string.download_state_failed)
            DownloadState.FINISH -> resources.getString(R.string.download_state_finish)
            else -> resources.getString(R.string.download_state_none)
        }
        val (finished, total) = mCallback.tankProgressFor(info.arcid)
        holder.state.text = if (total > 0) "$base $finished/$total" else base
    }

    private fun bindState(holder: DownloadHolder, info: DownloadInfo, state: String) {
        cancelProgressGlide(holder)
        applyTitleLines(holder, showingProgress = false)
        setVisibility(holder.uploader, View.VISIBLE)
        setVisibility(holder.rating, View.VISIBLE)
        setVisibility(holder.readProgress, View.VISIBLE)
        setVisibility(holder.state, View.VISIBLE)
        setVisibility(holder.progressBar, View.GONE)
        setVisibility(holder.percent, View.GONE)
        setVisibility(holder.speed, View.GONE)
        if (info.state == DownloadState.WAIT || info.state == DownloadState.DOWNLOAD) {
            setVisibility(holder.start, View.GONE)
            setVisibility(holder.stop, View.VISIBLE)
        } else {
            setVisibility(holder.start, View.VISIBLE)
            setVisibility(holder.stop, View.GONE)
        }
        holder.state.text = state
    }

    @SuppressLint("SetTextI18n")
    private fun bindProgress(holder: DownloadHolder, info: DownloadInfo, animate: Boolean) {
        applyTitleLines(holder, showingProgress = true)
        setVisibility(holder.uploader, View.GONE)
        setVisibility(holder.rating, View.GONE)
        setVisibility(holder.readProgress, View.GONE)
        setVisibility(holder.state, View.GONE)
        setVisibility(holder.progressBar, View.VISIBLE)
        setVisibility(holder.percent, View.VISIBLE)
        setVisibility(holder.speed, View.VISIBLE)
        if (info.state == DownloadState.WAIT || info.state == DownloadState.DOWNLOAD) {
            setVisibility(holder.start, View.GONE)
            setVisibility(holder.stop, View.VISIBLE)
        } else {
            setVisibility(holder.start, View.VISIBLE)
            setVisibility(holder.stop, View.GONE)
        }

        // Authoritative transient-progress source is the in-memory tracker,
        // not the Room-backed DownloadInfo (whose @Ignore fields are stale
        // copies from the scheduler's own instance and never reach this
        // Room-emitted instance). See ADR-001 Option D.
        val snap = mCallback.downloadManager?.progressFor(info.arcid)
        val speed = (snap?.speed ?: -1L).coerceAtLeast(0L)

        if (snap == null || snap.total <= 0 || snap.finished < 0) {
            cancelProgressGlide(holder)
            holder.percent.text = null
            holder.progressBar.isIndeterminate = true
        } else {
            // Page counter keeps the user-readable unit; the BAR advances
            // fractionally by bytes within pages (barMax/barProgress are
            // page-count × BAR_UNITS_PER_PAGE with in-flight fractions).
            holder.percent.text = "${snap.finished}/${snap.total}"
            holder.progressBar.isIndeterminate = false
            val max = snap.barMax()
            if (holder.progressBar.max != max) holder.progressBar.max = max
            val target = snap.barProgress()
            val current = holder.progressBar.progress
            cancelProgressGlide(holder)
            if (animate && target > current) {
                // The tracker publishes ~every 2 s (tick) — a glide spanning
                // that interval renders the discrete emissions as continuous
                // motion. Always toward the latest TRUE value, never past it
                // (no extrapolation); regressions (page retry reset) snap.
                holder.progressGlide = ObjectAnimator
                    .ofInt(holder.progressBar, "progress", target)
                    .apply {
                        duration = BAR_GLIDE_DURATION_MS
                        interpolator = LinearInterpolator()
                        start()
                    }
            } else {
                holder.progressBar.progress = target
            }
        }
        holder.speed.text = com.hippo.lib.yorozuya.FileUtils.humanReadableByteCount(speed, false) + "/S"
    }

    private fun cancelProgressGlide(holder: DownloadHolder) {
        holder.progressGlide?.cancel()
        holder.progressGlide = null
    }

    override fun onViewRecycled(holder: DownloadHolder) {
        cancelProgressGlide(holder)
        super.onViewRecycled(holder)
    }

    // 拖拽排序相关方法实现
    override fun onCheckCanStartDrag(holder: DownloadHolder, position: Int, x: Int, y: Int): Boolean {
        if (!DRAG_ENABLE) {
            return false
        }
        // Tank cards are synthetic rows without a DOWNLOAD_TIME of their
        // own — reordering them through moveDownloadInfo would corrupt
        // member times. Not draggable.
        val list = mCallback.list
        val pos = mCallback.positionInList(position)
        if (list != null && pos in list.indices && isTankoubonId(list[pos].arcid)) {
            return false
        }
        return ViewUtils.isViewUnder(holder.thumb, x, y, 0)
    }

    override fun onGetItemDraggableRange(holder: DownloadHolder, position: Int): ItemDraggableRange? {
        return null
    }

    override fun onMoveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) {
            return
        }
        val list = mCallback.list ?: return

        val fromPosInList = mCallback.positionInList(fromPosition)
        val toPosInList = mCallback.positionInList(toPosition)

        if (fromPosInList in 0 until list.size && toPosInList in 0 until list.size) {
            // A drop slot adjacent to a tank card still lands here — never
            // rotate DOWNLOAD_TIME across a synthetic row.
            if (isTankoubonId(list[fromPosInList].arcid) || isTankoubonId(list[toPosInList].arcid)) {
                return
            }
            // 先更新数据库中的顺序（通过 time 字段）
            ServiceRegistry.coroutineModule.ioScope.launch {
                ServiceRegistry.dataModule.downloadDbRepository.moveDownloadInfo(list, fromPosInList, toPosInList)
            }

            // 再尝试更新当前列表的内存顺序
            // list is declared as List<DownloadInfo> (unmodifiable from facade),
            // but the underlying instance may be mutable during drag-drop.
            // Use safe cast; if unmodifiable, only DB order is updated.
            val mutableList = list as? MutableList<DownloadInfo>
            if (mutableList != null) {
                val item = mutableList.removeAt(fromPosInList)
                mutableList.add(toPosInList, item)
            } else {
                Log.w(TAG, "onMoveItem: list is unmodifiable, only DB order updated")
            }

            notifyItemMoved(fromPosition, toPosition)
        }
    }

    override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean {
        return DRAG_ENABLE
    }

    override fun onItemDragStarted(position: Int) {
        try {
            val recyclerView = mCallback.recyclerView
            if (recyclerView != null) {
                movedItem = recyclerView.getChildAt(position)
                movedItem?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                Log.d("DownloadAdapter", "onItemDragStarted: $position")
            }
        } catch (e: Exception) {
            Log.e("DownloadAdapter", "Error in onItemDragStarted: ${e.message}")
        }
    }

    override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
        try {
            val recyclerView = mCallback.recyclerView
            if (recyclerView != null) {
                if (movedItem != null) {
                    movedItem!!.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    Log.d("DownloadAdapter", "movedItem: $movedItem")
                } else {
                    recyclerView.getChildAt(toPosition).setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    Log.d("DownloadAdapter", "onItemDragFinished: $toPosition")
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadAdapter", "Error in onItemDragFinished: ${e.message}")
        }
    }

    inner class DownloadHolder(itemView: View) :
        AbstractDraggableItemViewHolder(itemView), View.OnClickListener {

        @JvmField val thumb: LoadImageView = itemView.findViewById(R.id.thumb)
        @JvmField val title: TextView = itemView.findViewById(R.id.title)
        @JvmField val uploader: TextView = itemView.findViewById(R.id.uploader)
        @JvmField val rating: SimpleRatingView = itemView.findViewById(R.id.rating)
        @JvmField val category: TextView = itemView.findViewById(R.id.category)
        @JvmField val readProgress: TextView = itemView.findViewById(R.id.read_progress)
        @JvmField val sourceServer: TextView = itemView.findViewById(R.id.source_server)
        @JvmField val start: View = itemView.findViewById(R.id.start)
        @JvmField val stop: View = itemView.findViewById(R.id.stop)
        @JvmField val state: TextView = itemView.findViewById(R.id.state)
        @JvmField val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        @JvmField val percent: TextView = itemView.findViewById(R.id.percent)
        @JvmField val speed: TextView = itemView.findViewById(R.id.speed)

        /** Active glide toward the latest true bar value; cancelled on rebind/recycle. */
        var progressGlide: ObjectAnimator? = null

        init {
            // KNOWN-ISSUE (P2): click listeners remain active during multi-select mode
            thumb.setOnClickListener(this)
            start.setOnClickListener(this)
            stop.setOnClickListener(this)

            val isDarkTheme = !AttrResources.getAttrBoolean(
                mScene.ehContext!!, androidx.appcompat.R.attr.isLightTheme
            )
            Ripple.addRipple(start, isDarkTheme)
            Ripple.addRipple(stop, isDarkTheme)
        }

        override fun onClick(v: View) {
            val context = mScene.ehContext ?: return
            val recyclerView = mCallback.recyclerView
            if (recyclerView == null || recyclerView.isInCustomChoice) {
                return
            }
            val list = mCallback.list ?: return
            val size = list.size
            val index = recyclerView.getChildAdapterPosition(itemView)
            if (index < 0 || index >= size) {
                return
            }

            // Tank cards: the thumb behaves like the row (open the
            // whole-tank session via the scene); start/stop are hidden but
            // guard anyway — a TANK_ id must never reach the download
            // service or scheduler.
            if (isTankoubonId(list[mCallback.positionInList(index)].arcid)) {
                if (v === thumb) {
                    mScene.openTankCard(list[mCallback.positionInList(index)])
                }
                return
            }

            when (v) {
                thumb -> {
                    run {
                        val args = Bundle()
                        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_ARCHIVE)
                        args.putParcelable(
                            GalleryDetailScene.KEY_ARCHIVE,
                            list[mCallback.positionInList(index)].toArchive(),
                        )
                        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
                        announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
                        mScene.startScene(announcer)
                    }
                }
                start -> {
                    val info = list[mCallback.positionInList(index)]
                    val intent = Intent(context, DownloadService::class.java)
                    intent.action = DownloadService.ACTION_START
                    intent.putExtra(DownloadService.KEY_ARCHIVE, info.toArchive())
                    context.startService(intent)
                }
                stop -> {
                    val downloadManager = mCallback.downloadManager
                    downloadManager?.stopDownload(list[mCallback.positionInList(index)].arcid)
                }
            }
        }
    }

    companion object {
        private val TAG = DownloadAdapter::class.java.simpleName

        @JvmField
        var DRAG_ENABLE = false

        /** Title line budget when the row shows its download-progress line. */
        private const val TITLE_MAX_LINES_PROGRESS = 1

        /** Title line budget for every other state (the default from CardTitle). */
        private const val TITLE_MAX_LINES_DEFAULT = 2

        /**
         * Line budget for a download row's title.
         *
         * The row is a fixed height (driven by the 120dp thumb). While
         * progress is shown, the byte-progress line (`finished/total` on the
         * start edge, speed on the end edge) and the bar are pinned above the
         * bottom actions. A 2-line title, anchored to the top, drops its
         * second line into that band and collides with the `finished/total`
         * counter — they share the title's start edge (see the screenshot in
         * the bug report). Capping the title to one line while progress is
         * shown keeps it clear; the full two lines return for every other
         * state, where the info row flows *below* the title and cannot
         * collide.
         */
        internal fun downloadTitleMaxLines(showingProgress: Boolean): Int =
            if (showingProgress) TITLE_MAX_LINES_PROGRESS else TITLE_MAX_LINES_DEFAULT

        /** Bottom margin for source_server when the row's category is hidden (LRR default). */
        private const val BADGE_MARGIN_BOTTOM_DEFAULT_DP = 8

        /** Lifted bottom margin for the imported-archive branch where category is visible. */
        private const val BADGE_MARGIN_BOTTOM_WHEN_CATEGORY_VISIBLE_DP = 30

        /**
         * Glide duration just under the progress tracker's 2 s tick so the
         * bar is still moving when the next true value lands and retargets it.
         */
        private const val BAR_GLIDE_DURATION_MS = 1700L
    }
}
