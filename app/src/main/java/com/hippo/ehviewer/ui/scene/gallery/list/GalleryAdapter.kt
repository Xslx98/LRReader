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

package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.res.Resources
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IntDef
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.GalleryHolder
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.widget.TileThumb
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager

abstract class GalleryAdapter(
    private val mInflater: LayoutInflater,
    private val mResources: Resources,
    private val mRecyclerView: RecyclerView,
    type: Int,
    private var mShowFavourited: Boolean
) : RecyclerView.Adapter<GalleryHolder>() {

    @IntDef(TYPE_LIST, TYPE_GRID)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type

    private val mLayoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
    private val mPaddingTopSB: Int = mResources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
    private var mListDecoration: MarginItemDecoration? = null
    private var mGirdDecoration: MarginItemDecoration? = null
    private val mListThumbWidth: Int
    private val mListThumbHeight: Int
    private var mType = TYPE_INVALID

    private val mDownloadManager: DownloadManager

    init {
        mRecyclerView.adapter = this
        mRecyclerView.layoutManager = mLayoutManager

        @SuppressLint("InflateParams")
        val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
        mListThumbHeight = calculator.measuredHeight
        mListThumbWidth = mListThumbHeight * 2 / 3

        setType(type)

        mDownloadManager = ServiceRegistry.dataModule.downloadManager
    }

    private fun adjustPaddings() {
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
                adjustPaddings()
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
                adjustPaddings()
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
            TYPE_GRID -> R.layout.item_gallery_grid
            else -> R.layout.item_gallery_list
        }

        val holder = GalleryHolder(mInflater.inflate(layoutId, parent, false))

        if (viewType == TYPE_LIST) {
            val lp = holder.thumb.layoutParams
            lp.width = mListThumbWidth
            lp.height = mListThumbHeight
            holder.thumb.layoutParams = lp
        }

        return holder
    }

    override fun getItemViewType(position: Int): Int = mType

    abstract fun getDataAt(position: Int): GalleryInfo?

    override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
        val gi = getDataAt(position) ?: return

        when (mType) {
            TYPE_GRID -> {
                (holder.thumb as TileThumb).setThumbSize(gi.thumbWidth, gi.thumbHeight)
                holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
                // LANraragi doesn't use E-Hentai categories - hide triangle
                holder.category?.visibility = View.GONE
                holder.simpleLanguage?.text = gi.simpleLanguage
            }
            else -> {
                // TYPE_LIST or default
                holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
                holder.title?.text = EhUtils.getSuitableTitle(gi)
                holder.uploader?.text = gi.uploader
                holder.rating?.setRating(gi.rating)
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
                holder.favourited?.visibility =
                    if (mShowFavourited && gi.favoriteSlot in -1..10) View.VISIBLE else View.GONE
                holder.downloaded?.visibility =
                    if (mDownloadManager.containDownloadInfo(gi.gid)) View.VISIBLE else View.GONE
            }
        }

        // Update transition name
        ViewCompat.setTransitionName(holder.thumb, TransitionNameFactory.getThumbTransitionName(gi.gid))
    }

    companion object {
        const val TYPE_INVALID = -1
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
    }
}
