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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.hippo.ehviewer.gallery.A7ZipArchive
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hippo.ehviewer.download.DownloadState

/**
 * 下载列表适配器
 */
class DownloadAdapter(
    private val mScene: DownloadsScene,
    private val mCallback: DownloadAdapterCallback
) : RecyclerView.Adapter<DownloadAdapter.DownloadHolder>(),
    DraggableItemAdapter<DownloadAdapter.DownloadHolder> {

    private val mInflater: LayoutInflater
    private val mListThumbWidth: Int
    private val mListThumbHeight: Int

    private var movedItem: View? = null

    private val thumbnailCache = object : android.util.LruCache<String, Bitmap>(5 * 1024 * 1024) { // 5MB
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        // Intentionally no entryRemoved/recycle: eviction means "over the size
        // budget", not "no longer on screen". A RecyclerView holder may still be
        // displaying the evicted bitmap, and recycling it would crash with
        // "trying to use a recycled bitmap". ART (API 26+) reclaims the native
        // allocation via GC once nothing references it.
    }

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
                bindProgress(holder, info)
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
            val archiveUri = info.archiveUri
            val isImportedArchive = archiveUri != null && archiveUri.startsWith("content://")

            var title = archive.title
            if (isImportedArchive) {
                title = "📦 $title"
            }
            if (isImportedArchive && archiveUri != null) {
                loadArchiveThumbnail(holder.thumb, archiveUri.toUri())
            } else {
                holder.thumb.load(
                    LRRCacheKeyFactory.getThumbKey(info.arcid), archive.thumbnailUrl,
                    ThumbDataContainer(info), true, false
                )
            }

            holder.title.text = title
            holder.uploader.visibility = View.GONE

            if (isImportedArchive) {
                holder.rating.setRating(5.0f)
            } else if (archive.rating <= 0) {
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

            val category = holder.category
            if (isImportedArchive) {
                val categoryText = mScene.getString(R.string.imported_archive_category)
                category.text = categoryText
                category.setBackgroundColor(0xFF4CAF50.toInt())
                category.visibility = View.VISIBLE
            } else {
                category.visibility = View.GONE
            }

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

        // Check if this is an imported archive - skip state judging
        val archiveUri = info.archiveUri
        val isImportedArchive = archiveUri != null && archiveUri.startsWith("content://")
        if (isImportedArchive) {
            bindState(holder, info, resources.getString(R.string.download_state_finish))
            return
        }

        when (info.state) {
            DownloadState.INVALID,
            DownloadState.NONE -> bindState(holder, info, resources.getString(R.string.download_state_none))
            DownloadState.WAIT -> bindState(holder, info, resources.getString(R.string.download_state_wait))
            DownloadState.DOWNLOAD -> bindProgress(holder, info)
            DownloadState.FAILED -> {
                val text = if (info.legacy <= 0) {
                    resources.getString(R.string.download_state_failed)
                } else {
                    resources.getString(R.string.download_state_failed_2, info.legacy)
                }
                bindState(holder, info, text)
            }
            DownloadState.FINISH -> bindState(holder, info, resources.getString(R.string.download_state_finish))
        }
    }

    private fun setVisibility(view: View, visibility: Int) {
        if (view.visibility != visibility) view.visibility = visibility
    }

    private fun bindState(holder: DownloadHolder, info: DownloadInfo, state: String) {
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
    private fun bindProgress(holder: DownloadHolder, info: DownloadInfo) {
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
        val total = snap?.total ?: -1
        val finished = snap?.finished ?: 0
        val speed = (snap?.speed ?: -1L).coerceAtLeast(0L)

        if (total <= 0 || finished < 0) {
            holder.percent.text = null
            holder.progressBar.isIndeterminate = true
        } else {
            holder.percent.text = "$finished/$total"
            holder.progressBar.isIndeterminate = false
            holder.progressBar.max = total
            holder.progressBar.progress = finished
        }
        holder.speed.text = com.hippo.lib.yorozuya.FileUtils.humanReadableByteCount(speed, false) + "/S"
    }

    // 拖拽排序相关方法实现
    override fun onCheckCanStartDrag(holder: DownloadHolder, position: Int, x: Int, y: Int): Boolean {
        if (!DRAG_ENABLE) {
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

    private fun loadArchiveThumbnail(thumb: LoadImageView, archiveUri: Uri) {
        val uriString = archiveUri.toString()

        // Check cache first
        val cachedThumbnail = thumbnailCache.get(uriString)
        if (cachedThumbnail != null) {
            if (!cachedThumbnail.isRecycled) {
                thumb.setImageBitmap(cachedThumbnail)
                return
            } else {
                thumbnailCache.remove(uriString)
            }
        }

        // Set default icon immediately as fallback
        thumb.setImageResource(R.drawable.v_archive_hh_primary_x48)

        // Load thumbnail in background
        mScene.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val thumbnail = extractFirstImageFromArchive(archiveUri)
                mScene.runOnUiThread {
                    if (thumbnail != null && !thumbnail.isRecycled) {
                        thumbnailCache.put(uriString, thumbnail)
                        thumb.setImageBitmap(thumbnail)
                    } else {
                        val fallbackThumbnail = thumbnailCache.get(uriString)
                        if (fallbackThumbnail != null && !fallbackThumbnail.isRecycled) {
                            thumb.setImageBitmap(fallbackThumbnail)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load archive thumbnail for $uriString", e)
            }
        }
    }

    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod")
    private fun extractFirstImageFromArchive(archiveUri: Uri): Bitmap? {
        val context = mScene.ehContext ?: return null

        var uraf: com.hippo.unifile.UniRandomAccessFile? = null
        var archive: A7ZipArchive? = null

        try {
            // Verify URI accessibility first
            try {
                context.contentResolver.openInputStream(archiveUri)?.use { testStream ->
                    // Stream accessible
                } ?: run {
                    Log.w(TAG, "Cannot access archive URI: $archiveUri")
                    return null
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "URI permission lost, attempting to restore: $archiveUri", e)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        archiveUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d(TAG, "Successfully restored URI permission for: $archiveUri")
                    context.contentResolver.openInputStream(archiveUri)?.use { retryStream ->
                        // Stream accessible after restore
                    } ?: run {
                        Log.w(TAG, "Still cannot access URI after permission restore: $archiveUri")
                        return null
                    }
                } catch (restoreEx: Exception) {
                    Log.e(TAG, "Failed to restore URI permission for: $archiveUri", restoreEx)
                    return null
                }
            } catch (e: Exception) {
                Log.w(TAG, "URI not accessible: $archiveUri", e)
                return null
            }

            // Open the archive file
            val file = UniFile.fromUri(context, archiveUri)
            if (file == null || !file.exists()) {
                Log.w(TAG, "Archive file not found: $archiveUri")
                return null
            }

            uraf = file.createRandomAccessFile("r")
            if (uraf == null) {
                Log.w(TAG, "Cannot create random access file for: $archiveUri")
                return null
            }

            archive = A7ZipArchive.create(uraf)
            if (archive == null) {
                Log.w(TAG, "Cannot create archive reader for: $archiveUri")
                return null
            }

            val entries = archive.archiveEntries
            if (entries.isEmpty()) {
                Log.w(TAG, "Archive is empty: $archiveUri")
                return null
            }

            // Sort entries by name (natural order)
            val comparator = NaturalComparator()
            entries.sortWith { o1, o2 -> comparator.compare(o1.path, o2.path) }

            // Find the first image file
            for (entry in entries) {
                val fileName = entry.path.lowercase()
                if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                    fileName.endsWith(".png") || fileName.endsWith(".bmp") ||
                    fileName.endsWith(".gif") || fileName.endsWith(".webp")
                ) {
                    try {
                        // Extract image bytes once into memory
                        val baos = java.io.ByteArrayOutputStream(64 * 1024)
                        val pipe = Pipe(8 * 1024)
                        val extractThread = Thread {
                            try {
                                entry.extract(pipe.outputStream)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to extract image: $fileName", e)
                            } finally {
                                try { pipe.outputStream.close() } catch (e: Exception) { Log.d(TAG, "Close pipe output stream", e) }
                            }
                        }
                        extractThread.start()
                        try {
                            pipe.inputStream.copyTo(baos)
                        } finally {
                            try { pipe.inputStream.close() } catch (e: Exception) { Log.d(TAG, "Close pipe input stream", e) }
                        }
                        extractThread.join(3000)
                        val imageBytes = baos.toByteArray()
                        if (imageBytes.isEmpty()) {
                            Log.w(TAG, "Empty extraction result for $fileName")
                            continue
                        }

                        // Decode bounds from cached bytes
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

                        // Calculate sample size for thumbnail
                        val thumbnailSize = 150
                        var sampleSize = 1
                        if (options.outHeight > thumbnailSize || options.outWidth > thumbnailSize) {
                            val halfHeight = options.outHeight / 2
                            val halfWidth = options.outWidth / 2
                            while (halfHeight / sampleSize >= thumbnailSize && halfWidth / sampleSize >= thumbnailSize) {
                                sampleSize *= 2
                            }
                        }

                        // Decode actual bitmap from cached bytes
                        options.inJustDecodeBounds = false
                        options.inSampleSize = sampleSize
                        options.inPreferredConfig = Bitmap.Config.RGB_565
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

                        if (bitmap != null && !bitmap.isRecycled) {
                            Log.d(TAG, "Successfully extracted thumbnail from $fileName")
                            return bitmap
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract thumbnail from $fileName", e)
                    }
                }
            }

            Log.w(TAG, "No extractable images found in archive: $archiveUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process archive for thumbnail: $archiveUri", e)
        } finally {
            try { archive?.close() } catch (e: Exception) {
                Log.w(TAG, "Failed to close archive", e)
            }
            try { uraf?.close() } catch (e: Exception) {
                Log.w(TAG, "Failed to close file", e)
            }
        }

        return null
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

            when (v) {
                thumb -> {
                    val currentInfo = list[mScene.positionInList(index)]
                    val currentArchiveUri = currentInfo.archiveUri
                    if (currentArchiveUri != null && currentArchiveUri.startsWith("content://")) {
                        val message = mScene.getString(R.string.imported_archive_info_message) +
                                "\n\n" + currentArchiveUri
                        AlertDialog.Builder(context)
                            .setTitle(R.string.imported_archive_info_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    } else {
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

        /** Bottom margin for source_server when the row's category is hidden (LRR default). */
        private const val BADGE_MARGIN_BOTTOM_DEFAULT_DP = 8

        /** Lifted bottom margin for the imported-archive branch where category is visible. */
        private const val BADGE_MARGIN_BOTTOM_WHEN_CATEGORY_VISIBLE_DP = 30
    }
}
