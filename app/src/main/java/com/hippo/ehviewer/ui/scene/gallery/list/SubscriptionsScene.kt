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

import android.content.Context
import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.userTag.UserTag
import com.hippo.ehviewer.client.data.userTag.UserTagList
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.scene.SceneFragment
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import com.hippo.widget.ProgressView

class SubscriptionsScene : ToolbarScene() {

    /*---------------
     Whole life cycle
     ---------------*/
    private var userTagList: UserTagList? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var progressView: ProgressView? = null
    private var mViewTransition: ViewTransition? = null

    private var ehTags: EhTagDatabase? = null
    private var context: Context? = null
    private var ehClient: EhClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = ehContext
        if (ehClient == null) {
            ehClient = ServiceRegistry.clientModule.ehClient
        }
        userTagList = ServiceRegistry.dataModule.userTagList
        ehTags = context?.let { EhTagDatabase.getInstance(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        userTagList = null
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_label_list, container, false)
        progressView = view.findViewById(R.id.scene_label_progress)
        mRecyclerView = ViewUtils.`$$`(view, R.id.recycler_view) as EasyRecyclerView
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        mViewTransition = ViewTransition(mRecyclerView, tip)

        val context = ehContext!!
        AssertUtils.assertNotNull(context)

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_search)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setText(R.string.no_quick_search)

        // drag & drop manager
        val dragDropManager = RecyclerViewDragDropManager()
        dragDropManager.setDraggingItemShadowDrawable(
            AppCompatResources.getDrawable(context!!, R.drawable.shadow_8dp) as NinePatchDrawable
        )

        var adapter: RecyclerView.Adapter<*> = QuickSearchAdapter()
        adapter.setHasStableIds(true)
        adapter = dragDropManager.createWrappedAdapter(adapter) // wrap for dragging

        val animator = DraggableItemAnimator()
        mRecyclerView!!.layoutManager = LinearLayoutManager(context)
        mRecyclerView!!.adapter = adapter
        mRecyclerView!!.itemAnimator = animator

        dragDropManager.attachRecyclerView(mRecyclerView!!)

        updateView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.tag_title)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mRecyclerView?.stopScroll()
        mRecyclerView = null

        mViewTransition = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    private fun bindSecond() {
        progressView?.visibility = View.GONE
        if (mRecyclerView == null) {
            Toast.makeText(context, context!!.getString(R.string.lrr_subscription_profile_missing), Toast.LENGTH_LONG).show()
            return
        }
        mRecyclerView!!.visibility = View.VISIBLE
        if (userTagList == null) {
            userTagList = UserTagList()
            userTagList!!.userTags = ArrayList()
        }
        // drag & drop manager
        val dragDropManager = RecyclerViewDragDropManager()
        dragDropManager.setDraggingItemShadowDrawable(
            AppCompatResources.getDrawable(context!!, R.drawable.shadow_8dp) as NinePatchDrawable
        )

        var adapter: RecyclerView.Adapter<*> = QuickSearchAdapter()
        adapter.setHasStableIds(true)
        adapter = dragDropManager.createWrappedAdapter(adapter) // wrap for dragging

        val animator = DraggableItemAnimator()
        mRecyclerView!!.layoutManager = LinearLayoutManager(context)
        mRecyclerView!!.adapter = adapter
        mRecyclerView!!.itemAnimator = animator

        dragDropManager.attachRecyclerView(mRecyclerView!!)
    }

    private fun updateView() {
        mViewTransition?.let {
            if (userTagList != null && userTagList!!.userTags.size > 0) {
                it.showView(0)
            } else {
                it.showView(1)
            }
        }
    }

    private inner class SubscriptionHolder(itemView: View) :
        AbstractDraggableItemViewHolder(itemView), View.OnClickListener {

        val label: TextView = ViewUtils.`$$`(itemView, R.id.label) as TextView
        val dragHandler: View = ViewUtils.`$$`(itemView, R.id.drag_handler)
        val delete: View = ViewUtils.`$$`(itemView, R.id.delete)
        var imageView: ImageView = itemView.findViewById(R.id.drag_handler)

        init {
            delete.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val position = adapterPosition
            val context = ehContext
            if (position == RecyclerView.NO_POSITION || userTagList == null) {
                return
            }

            val userTag = userTagList!!.userTags[position]
            AlertDialog.Builder(context!!)
                .setTitle(R.string.delete_subscription_title)
                .setMessage(getString(R.string.delete_quick_search_message, userTag.tagName))
                .setPositiveButton(android.R.string.ok) { _, _ -> deleteTag(userTag) }
                .show()
        }

        private fun deleteTag(userTag: UserTag) {
            progressView?.visibility = View.VISIBLE
            mRecyclerView!!.visibility = View.INVISIBLE
            deleteRequest(userTag)
        }

        private fun deleteRequest(userTag: UserTag) {
            val url = EhUrl.getMyTag()

            if (context == null) {
                return
            }
            val callback: EhClient.Callback<UserTagList> =
                SubscriptionDetailListener(context!!, userTagList!!.stageId, tag ?: "")

            val mRequest = EhRequest()
                .setMethod(EhClient.METHOD_DELETE_WATCHED)
                .setArgs(url, userTag).setCallback(callback)

            ehClient!!.execute(mRequest)
        }
    }

    private inner class QuickSearchAdapter : RecyclerView.Adapter<SubscriptionHolder>() {

        private val mInflater: LayoutInflater = layoutInflater2!!.also {
            AssertUtils.assertNotNull(it)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionHolder {
            return SubscriptionHolder(mInflater.inflate(R.layout.item_subscription, parent, false))
        }

        override fun onBindViewHolder(holder: SubscriptionHolder, position: Int) {
            if (userTagList != null) {
                if (AppearanceSettings.getShowTagTranslations()) {
                    holder.label.text = userTagList!!.userTags[position].getName(ehTags!!)
                } else {
                    holder.label.text = userTagList!!.userTags[position].tagName
                }
            }
            if (userTagList!![position].hidden) {
                holder.imageView.setImageResource(R.drawable.ic_baseline_visibility_off_24)
            }
            if (userTagList!![position].watched) {
                holder.imageView.setImageResource(R.drawable.ic_baseline_visibility_24)
            }
        }

        override fun getItemId(position: Int): Long {
            return if (userTagList != null) userTagList!!.userTags[position].getId() else 0
        }

        override fun getItemCount(): Int {
            return if (userTagList != null) userTagList!!.userTags.size else 0
        }
    }

    private inner class SubscriptionDetailListener(
        context: Context,
        stageId: Int,
        sceneTag: String
    ) : EhCallback<GalleryListScene, UserTagList>(context, stageId, sceneTag) {

        override fun isInstance(scene: SceneFragment?): Boolean = false

        override fun onSuccess(result: UserTagList) {
            if (userTagList == null) {
                userTagList = UserTagList()
            }
            if (result.userTags == null) {
                userTagList!!.userTags = ArrayList()
            } else {
                userTagList!!.userTags = result.userTags
            }
            ServiceRegistry.dataModule.saveUserTagList(result)
            bindSecond()
        }

        override fun onFailure(e: Exception) {}

        override fun onCancel() {}
    }
}
