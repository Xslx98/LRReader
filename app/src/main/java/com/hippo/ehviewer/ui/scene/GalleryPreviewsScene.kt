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

package com.hippo.ehviewer.ui.scene

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryPreview
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.event.GalleryActivityEvent
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.scene.SceneFragment
import com.hippo.widget.ContentLayout
import com.hippo.widget.LoadImageView
import com.hippo.widget.Slider
import com.hippo.widget.recyclerview.AutoGridLayoutManager
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.LayoutUtils
import com.hippo.lib.yorozuya.ViewUtils
import java.util.Locale

class GalleryPreviewsScene : ToolbarScene(), EasyRecyclerView.OnItemClickListener {

    /*---------------
     Whole life cycle
     ---------------*/
    private var mClient: EhClient? = null
    private var mGalleryInfo: GalleryInfo? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mAdapter: GalleryPreviewAdapter? = null
    private var mHelper: GalleryPreviewHelper? = null

    private var mHasFirstRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        mClient = ServiceRegistry.clientModule.ehClient
        onInit()
    }

    private fun onInit() {
        val args = arguments ?: return
        mGalleryInfo = args.getParcelable(KEY_GALLERY_INFO)
    }

    @Suppress("unused")
    private fun onRestore(savedInstanceState: Bundle) {
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO)
        mHasFirstRefresh = savedInstanceState.getBoolean(KEY_HAS_FIRST_REFRESH)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // intentionally empty — state restore not used
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val contentLayout = inflater.inflate(
            R.layout.scene_gallery_previews, container, false
        ) as ContentLayout
        contentLayout.hideFastScroll()
        mRecyclerView = contentLayout.recyclerView

        val context = ehContext
        AssertUtils.assertNotNull(context)
        val resources = context!!.resources

        mAdapter = GalleryPreviewAdapter()
        mRecyclerView!!.adapter = mAdapter
        val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getThumbSizeResId())
        val layoutManager = AutoGridLayoutManager(context, columnWidth)
        layoutManager.setStrategy(AutoGridLayoutManager.STRATEGY_SUITABLE_SIZE)
        mRecyclerView!!.layoutManager = layoutManager
        mRecyclerView!!.clipToPadding = false
        mRecyclerView!!.setOnItemClickListener(this)
        val padding = LayoutUtils.dp2pix(context, 4f)
        val decoration = MarginItemDecoration(padding, padding, padding, padding, padding)
        mRecyclerView!!.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)

        mHelper = GalleryPreviewHelper()
        contentLayout.setHelper(mHelper)

        // Only refresh for the first time
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper!!.firstRefresh()
        }

        return contentLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (mHelper != null) {
            if (mHelper!!.shownViewIndex == 1) {
                mHasFirstRefresh = false
            }
        }
        if (mRecyclerView != null) {
            mRecyclerView!!.stopScroll()
            mRecyclerView = null
        }

        mAdapter = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.gallery_previews)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    override fun getMenuResId(): Int = R.menu.scene_gallery_previews

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val context = ehContext ?: return false

        when (item.itemId) {
            R.id.action_go_to -> {
                val helper = mHelper ?: return true
                val pages = helper.pages
                if (pages > 0 && helper.canGoTo()) {
                    val goToHelper = GoToDialogHelper(pages, helper.pageForTop)
                    val dialog = AlertDialog.Builder(context)
                        .setTitle(R.string.go_to)
                        .setView(R.layout.dialog_go_to)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    dialog.show()
                    goToHelper.setDialog(dialog)
                }
                return true
            }
        }
        return false
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun onItemClick(
        parent: EasyRecyclerView,
        view: View,
        position: Int,
        id: Long
    ): Boolean {
        val context = ehContext
        if (context != null && mHelper != null && mGalleryInfo != null) {
            val p = mHelper!!.getDataAtEx(position)
            if (p != null) {
                try {
                    val intent = Intent(context, GalleryActivity::class.java)
                    intent.action = GalleryActivity.ACTION_EH
                    intent.putExtra(GalleryActivity.DATA_IN_EVENT, true)
                    startActivity(intent)
                    AppEventBus.postGalleryActivityEvent(
                        GalleryActivityEvent(p.position, mGalleryInfo)
                    )
                } catch (e: RuntimeException) {
                    Analytics.recordException(e)
                }
            }
        }
        return true
    }

    private inner class GalleryPreviewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: LoadImageView = itemView.findViewById(R.id.image)
        val text: TextView = itemView.findViewById(R.id.text)
    }

    private inner class GalleryPreviewAdapter : RecyclerView.Adapter<GalleryPreviewHolder>() {

        private val mInflater: LayoutInflater = layoutInflater2

        init {
            AssertUtils.assertNotNull(mInflater)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryPreviewHolder {
            return GalleryPreviewHolder(
                mInflater.inflate(R.layout.item_gallery_preview, parent, false)
            )
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: GalleryPreviewHolder, position: Int) {
            if (mHelper != null) {
                val preview = mHelper!!.getDataAtEx(position)
                if (preview != null) {
                    preview.load(holder.image)
                    holder.text.text = (preview.position + 1).toString()
                }
            }
        }

        override fun getItemCount(): Int = mHelper?.size() ?: 0
    }

    private inner class GalleryPreviewHelper : ContentLayout.ContentHelper<GalleryPreview>() {

        override fun getPageData(taskId: Int, type: Int, page: Int) {
            val activity = activity2
            if (activity == null || mClient == null || mGalleryInfo == null) {
                try {
                    onGetException(taskId, EhException(getString(R.string.error_cannot_find_gallery)))
                } catch (_: IllegalStateException) {
                    // ignored
                }
                return
            }

            val url = EhUrl.getGalleryDetailUrl(mGalleryInfo!!.gid, mGalleryInfo!!.token, page, false)
            val request = EhRequest()
            request.setMethod(EhClient.METHOD_GET_PREVIEW_SET)
            request.setCallback(
                GetPreviewSetListener(context, activity.stageId, tag, taskId)
            )
            request.setArgs(url)
            mClient!!.execute(request)
        }

        override fun getPageData(taskId: Int, type: Int, page: Int, append: String) {
            // empty
        }

        override fun getExPageData(pageAction: Int, taskId: Int, page: Int) {
            val activity = activity2
            if (activity == null || mClient == null || mGalleryInfo == null) {
                onGetException(taskId, EhException(getString(R.string.error_cannot_find_gallery)))
                return
            }

            val url = EhUrl.getGalleryDetailUrl(mGalleryInfo!!.gid, mGalleryInfo!!.token, page, false)
            val request = EhRequest()
            request.setMethod(EhClient.METHOD_GET_PREVIEW_SET)
            request.setCallback(
                GetPreviewSetListener(context, activity.stageId, tag, taskId)
            )
            request.setArgs(url)
            mClient!!.execute(request)
        }

        override fun getContext(): Context? {
            return this@GalleryPreviewsScene.ehContext
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun notifyDataSetChanged() {
            mAdapter?.notifyDataSetChanged()
        }

        override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) {
            mAdapter?.notifyItemRangeRemoved(positionStart, itemCount)
        }

        override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) {
            mAdapter?.notifyItemRangeInserted(positionStart, itemCount)
        }

        override fun isDuplicate(d1: GalleryPreview, d2: GalleryPreview): Boolean = false
    }

    private fun onGetPreviewSetSuccess(result: Pair<PreviewSet, Int>, taskId: Int) {
        if (mHelper != null && mHelper!!.isCurrentTask(taskId) && mGalleryInfo != null) {
            val previewSet = result.first
            val size = previewSet.size()
            val list = ArrayList<GalleryPreview>(size)
            for (i in 0 until size) {
                list.add(previewSet.getGalleryPreview(mGalleryInfo!!.gid, i))
            }
            mHelper!!.onGetPageData(taskId, result.second, 0, list)
        }
    }

    private fun onGetPreviewSetFailure(e: Exception, taskId: Int) {
        if (mHelper != null && mHelper!!.isCurrentTask(taskId)) {
            mHelper!!.onGetException(taskId, e)
        }
    }

    private class GetPreviewSetListener(
        context: Context?,
        stageId: Int,
        sceneTag: String?,
        private val mTaskId: Int
    ) : EhCallback<GalleryPreviewsScene, Pair<PreviewSet, Int>>(context, stageId, sceneTag) {

        override fun onSuccess(result: Pair<PreviewSet, Int>) {
            val scene = scene
            scene?.onGetPreviewSetSuccess(result, mTaskId)
        }

        override fun onFailure(e: Exception) {
            val scene = scene
            scene?.onGetPreviewSetFailure(e, mTaskId)
        }

        override fun onCancel() {
            // empty
        }

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is GalleryPreviewsScene
        }
    }

    private inner class GoToDialogHelper(
        private val mPages: Int,
        private val mCurrentPage: Int
    ) : View.OnClickListener, DialogInterface.OnDismissListener {

        private var mSlider: Slider? = null
        private var mDialog: Dialog? = null

        fun setDialog(dialog: AlertDialog) {
            mDialog = dialog

            (ViewUtils.`$$`(dialog, R.id.start) as TextView).text =
                String.format(Locale.US, "%d", 1)
            (ViewUtils.`$$`(dialog, R.id.end) as TextView).text =
                String.format(Locale.US, "%d", mPages)
            mSlider = ViewUtils.`$$`(dialog, R.id.slider) as Slider
            mSlider!!.setRange(1, mPages)
            mSlider!!.progress = mCurrentPage + 1

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(this)
            dialog.setOnDismissListener(this)
        }

        override fun onClick(v: View) {
            val slider = mSlider ?: return

            val page = slider.progress - 1
            if (page in 0 until mPages && mHelper != null) {
                mHelper!!.goTo(page)
                mDialog?.dismiss()
                mDialog = null
            } else {
                showTip(R.string.error_out_of_range, LENGTH_LONG)
            }
        }

        override fun onDismiss(dialog: DialogInterface) {
            mDialog = null
            mSlider = null
        }
    }

    companion object {
        const val KEY_GALLERY_INFO = "gallery_info"
        private const val KEY_HAS_FIRST_REFRESH = "has_first_refresh"
    }
}
