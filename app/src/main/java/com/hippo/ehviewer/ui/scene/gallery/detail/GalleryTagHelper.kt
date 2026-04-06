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
package com.hippo.ehviewer.ui.scene.gallery.detail

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.RoundSideRectDrawable
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListSceneDialog
import com.hippo.widget.AutoWrapLayout

/**
 * Handles tag display, tag filtering dialogs, and tag long-press actions
 * extracted from GalleryDetailScene to reduce its line count.
 */
class GalleryTagHelper(private val callback: Callback) {

    interface Callback {
        fun getContext(): Context?
        fun getInflater(): LayoutInflater?
        fun getTagsLayout(): LinearLayout?
        fun getNoTagsView(): TextView?
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
        fun showTip(resId: Int, length: Int)
        fun getUploader(): String?
        fun getTagClickListener(): View.OnClickListener
        fun getTagLongClickListener(): View.OnLongClickListener
    }

    private var ehTags: EhTagDatabase? = null
    private var tagDialog: GalleryListSceneDialog? = null

    fun getEhTags(): EhTagDatabase? = ehTags

    fun setTagDialog(dialog: GalleryListSceneDialog?) {
        tagDialog = dialog
    }

    /**
     * Populate the tags LinearLayout with tag group chips.
     */
    fun bindTags(tagGroups: Array<GalleryTagGroup>?) {
        val context = callback.getContext()
        val inflater = callback.getInflater()
        val mTags = callback.getTagsLayout()
        val mNoTags = callback.getNoTagsView()
        if (context == null || inflater == null || mTags == null || mNoTags == null) {
            return
        }

        mTags.removeViews(1, mTags.childCount - 1)
        if (tagGroups == null || tagGroups.isEmpty()) {
            mNoTags.visibility = View.VISIBLE
            return
        } else {
            mNoTags.visibility = View.GONE
        }

        ehTags = if (AppearanceSettings.getShowTagTranslations()) {
            EhTagDatabase.getInstance(context)
        } else {
            null
        }

        val colorTag = AttrResources.getAttrColor(context, R.attr.tagBackgroundColor)
        val colorName = AttrResources.getAttrColor(context, R.attr.tagGroupBackgroundColor)
        val clickListener = callback.getTagClickListener()
        val longClickListener = callback.getTagLongClickListener()

        for (tg in tagGroups) {
            val ll = inflater.inflate(R.layout.gallery_tag_group, mTags, false) as LinearLayout
            ll.orientation = LinearLayout.HORIZONTAL
            mTags.addView(ll)

            var readableTagName: String? = null
            if (ehTags != null) {
                readableTagName = ehTags!!.getTranslation("n:" + tg.groupName)
            }

            val tgName = inflater.inflate(R.layout.item_gallery_tag, ll, false) as TextView
            ll.addView(tgName)
            tgName.text = readableTagName ?: tg.groupName
            tgName.background = RoundSideRectDrawable(colorName)

            val prefix = tg.groupName?.let { EhTagDatabase.namespaceToPrefix(it) } ?: ""

            val awl = AutoWrapLayout(context)
            ll.addView(awl, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            for (j in 0 until tg.size()) {
                val tag = inflater.inflate(R.layout.item_gallery_tag, awl, false) as TextView
                awl.addView(tag)
                val tagStr = tg.getTagAt(j)

                var readableTag: String? = null
                if (ehTags != null) {
                    readableTag = ehTags!!.getTranslation(prefix + tagStr)
                }

                tag.text = readableTag ?: tagStr
                tag.background = RoundSideRectDrawable(colorTag)
                tag.setTag(R.id.tag, tg.groupName + ":" + tagStr)
                tag.setOnClickListener(clickListener)
                tag.setOnLongClickListener(longClickListener)
            }
        }
    }

    /**
     * Show a dialog to filter the current uploader.
     */
    fun showFilterUploaderDialog() {
        val context = callback.getContext()
        val uploader = callback.getUploader()
        if (context == null || uploader == null) {
            return
        }

        AlertDialog.Builder(context)
            .setMessage(callback.getString(R.string.filter_the_uploader, uploader))
            .setPositiveButton(android.R.string.ok) { _, which ->
                if (which != DialogInterface.BUTTON_POSITIVE) {
                    return@setPositiveButton
                }

                val filter = Filter()
                filter.mode = EhFilter.MODE_UPLOADER
                filter.text = uploader
                EhFilter.getInstance().addFilter(filter)

                callback.showTip(R.string.filter_added, LENGTH_SHORT)
            }.show()
    }

    /**
     * Show a dialog to filter the given tag.
     */
    fun showFilterTagDialog(tag: String) {
        val context = callback.getContext() ?: return

        AlertDialog.Builder(context)
            .setMessage(callback.getString(R.string.filter_the_tag, tag))
            .setPositiveButton(android.R.string.ok) { _, which ->
                if (which != DialogInterface.BUTTON_POSITIVE) {
                    return@setPositiveButton
                }

                val filter = Filter()
                filter.mode = EhFilter.MODE_TAG
                filter.text = tag
                EhFilter.getInstance().addFilter(filter)

                callback.showTip(R.string.filter_added, LENGTH_SHORT)
            }.show()
    }

    /**
     * Show the tag long-press dialog with options (search, filter, copy, etc.).
     */
    fun showTagDialog(baseScene: com.hippo.ehviewer.ui.scene.BaseScene, tag: String) {
        if (tagDialog == null) {
            tagDialog = GalleryListSceneDialog(baseScene)
        }
        if (ehTags == null) {
            ehTags = callback.getContext()?.let { EhTagDatabase.getInstance(it) }
        }
        tagDialog!!.setTagName(tag)
        tagDialog!!.showTagLongPressDialog(ehTags)
    }

    /**
     * Clean up references (call from onDestroyView).
     */
    fun destroy() {
        ehTags = null
        tagDialog = null
    }

    companion object {
        private const val LENGTH_SHORT = 0
    }
}
