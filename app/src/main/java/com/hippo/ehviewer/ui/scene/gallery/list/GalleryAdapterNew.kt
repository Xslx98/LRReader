/*
 * Copyright 2021 xiaojieonly
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

package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.res.Resources
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IntDef
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.client.LRRUtils
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.widget.SimpleRatingView
import com.hippo.ehviewer.widget.TileThumbNew
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.widget.LoadImageViewNew
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager

abstract class GalleryAdapterNew(
    private val mInflater: LayoutInflater,
    private val mResources: Resources,
    private val mRecyclerView: RecyclerView,
    type: Int,
    private var mShowFavourite: Boolean
) : RecyclerView.Adapter<GalleryAdapterNew.GalleryHolder>() {

    @IntDef(TYPE_LIST, TYPE_GRID)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type

    private val mLayoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
    private val mPaddingTopSB: Int = mResources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
    private var mListDecoration: MarginItemDecoration? = null
    private var mGirdDecoration: MarginItemDecoration? = null
    private val mListThumbHeight: Int by lazy {
        val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
        calculator.measuredHeight
    }
    private val mListThumbWidth: Int by lazy { mListThumbHeight * 2 / 3 }
    private var mType = TYPE_INVALID
    private var myOnThumbItemClickListener: OnThumbItemClickListener? = null

    private val mDownloadManager: DownloadManager

    init {
        mRecyclerView.adapter = this
        mRecyclerView.layoutManager = mLayoutManager
        mRecyclerView.setHasFixedSize(true)
        mRecyclerView.setItemViewCacheSize(20) // Cache more VHs for smoother scrolling

        setType(type)

        mDownloadManager = ServiceRegistry.dataModule.downloadManager
    }

    private fun adjustPadding() {
        mRecyclerView.setPadding(
            mRecyclerView.paddingLeft,
            mRecyclerView.paddingTop + mPaddingTopSB,
            mRecyclerView.paddingRight,
            mRecyclerView.paddingBottom
        )
    }

    fun getType(): Int = mType

    @SuppressLint("NotifyDataSetChanged")
    fun setType(type: Int) {
        if (type == mType) {
            return
        }
        mType = type

        when (type) {
            TYPE_GRID -> {
                val columnWidth = mResources.getDimensionPixelOffset(AppearanceSettings.getThumbSizeResId())
                mLayoutManager.setColumnSize(columnWidth)
                mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_SUITABLE_SIZE)
                mListDecoration?.let { mRecyclerView.removeItemDecoration(it) }
                if (mGirdDecoration == null) {
                    val interval = mResources.getDimensionPixelOffset(R.dimen.gallery_grid_interval)
                    val paddingH = mResources.getDimensionPixelOffset(R.dimen.gallery_grid_margin_h)
                    val paddingV = mResources.getDimensionPixelOffset(R.dimen.gallery_grid_margin_v)
                    mGirdDecoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
                }
                mRecyclerView.addItemDecoration(mGirdDecoration!!)
                mGirdDecoration!!.applyPaddings(mRecyclerView)
                adjustPadding()
                notifyDataSetChanged()
            }
            else -> {
                // TYPE_LIST or default
                val columnWidth = mResources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
                mLayoutManager.setColumnSize(columnWidth)
                mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)
                mGirdDecoration?.let { mRecyclerView.removeItemDecoration(it) }
                if (mListDecoration == null) {
                    val interval = mResources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
                    val paddingH = mResources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
                    val paddingV = mResources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
                    mListDecoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
                }
                mRecyclerView.addItemDecoration(mListDecoration!!)
                mListDecoration!!.applyPaddings(mRecyclerView)
                adjustPadding()
                notifyDataSetChanged()
            }
        }
    }

    /**
     * Re-read detail/thumb size from settings and update the layout manager column size.
     * Call this from onResume() to pick up changes made in settings without requiring a mode toggle.
     */
    fun refreshColumnSize() {
        val columnWidth = when (mType) {
            TYPE_GRID -> mResources.getDimensionPixelOffset(AppearanceSettings.getThumbSizeResId())
            else -> mResources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
        }
        mLayoutManager.setColumnSize(columnWidth)
        mRecyclerView.requestLayout()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryHolder {
        val layoutId = when (viewType) {
            TYPE_GRID -> R.layout.item_gallery_grid_new
            else -> R.layout.item_gallery_list_new
        }

        val holder = GalleryHolder(
            mInflater.inflate(layoutId, parent, false),
            myOnThumbItemClickListener,
            viewType
        )

        if (viewType == TYPE_LIST) {
            val lp = holder.thumb.layoutParams
            lp.width = mListThumbWidth
            lp.height = mListThumbHeight
            holder.thumb.layoutParams = lp
        }

        return holder
    }

    override fun getItemViewType(position: Int): Int = mType

    open fun getDataAt(position: Int): GalleryInfoUi? = null

    override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
        val gi = getDataAt(position) ?: return

        when (mType) {
            TYPE_GRID -> {
                (holder.thumb as TileThumbNew).setThumbSize(gi.thumbWidth, gi.thumbHeight)
                holder.thumb.load(LRRCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
                // LANraragi doesn't use E-Hentai categories - hide triangle
                holder.category?.visibility = View.GONE
                holder.simpleLanguage?.text = gi.simpleLanguage
            }
            else -> {
                // TYPE_LIST or default
                holder.thumb.load(LRRCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
                holder.title?.text = LRRUtils.getSuitableTitle(gi)
                holder.uploader?.text = gi.uploader
                if (!AppearanceSettings.getShowGalleryRating()) {
                    holder.rating?.visibility = View.INVISIBLE
                } else {
                    holder.rating?.setRating(gi.rating)
                }

                // LANraragi doesn't use E-Hentai categories - hide badge
                holder.category?.visibility = View.GONE
                holder.posted?.text = gi.posted
                if (gi.pages == 0 || !AppearanceSettings.getShowGalleryPages()) {
                    holder.pages?.text = null
                    holder.pages?.visibility = View.GONE
                } else {
                    val displayProgress = if (gi.progress > 0) gi.progress else 1
                    holder.pages?.text = "${displayProgress}/${gi.pages}P"
                    holder.pages?.visibility = View.VISIBLE
                }
                if (TextUtils.isEmpty(gi.simpleLanguage)) {
                    holder.simpleLanguage?.text = null
                    holder.simpleLanguage?.visibility = View.GONE
                } else {
                    holder.simpleLanguage?.text = gi.simpleLanguage
                    holder.simpleLanguage?.visibility = View.VISIBLE
                }
                holder.favourite?.visibility =
                    if (mShowFavourite && gi.favoriteSlot in -1..10) View.VISIBLE else View.GONE
                holder.downloaded?.visibility =
                    if (mDownloadManager.containDownloadInfo(gi.gid)) View.VISIBLE else View.GONE
            }
        }
        ViewCompat.setTransitionName(holder.thumb, TransitionNameFactory.getThumbTransitionName(gi.gid))
    }

    fun setThumbItemClickListener(listener: OnThumbItemClickListener?) {
        myOnThumbItemClickListener = listener
    }

    interface OnThumbItemClickListener {
        fun onThumbItemClick(position: Int, view: View, gi: GalleryInfoUi?)
    }

    inner class GalleryHolder(
        itemView: View,
        onThumbItemClickListener: OnThumbItemClickListener?,
        mType: Int
    ) : RecyclerView.ViewHolder(itemView) {

        @JvmField val thumb: LoadImageViewNew = itemView.findViewById(R.id.thumb_new)
        @JvmField var title: TextView? = itemView.findViewById(R.id.title)
        @JvmField val uploader: TextView? = itemView.findViewById(R.id.uploader)
        @JvmField val rating: SimpleRatingView? = itemView.findViewById(R.id.rating)
        @JvmField val category: TextView? = itemView.findViewById(R.id.category)
        @JvmField val posted: TextView? = itemView.findViewById(R.id.posted)
        @JvmField val pages: TextView? = itemView.findViewById(R.id.pages)
        @JvmField val simpleLanguage: TextView? = itemView.findViewById(R.id.simple_language)
        @JvmField val favourite: ImageView? = itemView.findViewById(R.id.favourited)
        @JvmField val downloaded: ImageView? = itemView.findViewById(R.id.downloaded)

        init {
            if (mType == 0) {
                thumb.setOnClickListener {
                    if (onThumbItemClickListener != null) {
                        val position = bindingAdapterPosition
                        onThumbItemClickListener.onThumbItemClick(position, itemView, getDataAt(position))
                    }
                }
            }
        }
    }

    companion object {
        const val TYPE_INVALID = -1
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
    }
}
