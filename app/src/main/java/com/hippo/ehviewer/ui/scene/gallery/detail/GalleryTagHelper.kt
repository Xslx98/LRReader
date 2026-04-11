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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.RoundSideRectDrawable
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListSceneDialog
import com.hippo.widget.AutoWrapLayout

/**
 * Stateless utility for tag display and tag long-press actions,
 * extracted from GalleryDetailScene. All methods take explicit parameters;
 * no Callback interface.
 */
object GalleryTagHelper {

    /** Cached [EhTagDatabase] instance from the last [bindTags] call. */
    private var ehTags: EhTagDatabase? = null

    /** Cached [GalleryListSceneDialog] instance for tag long-press. */
    private var tagDialog: GalleryListSceneDialog? = null

    fun getEhTags(): EhTagDatabase? = ehTags

    /**
     * Populate the tags LinearLayout with tag group chips.
     */
    fun bindTags(
        context: Context,
        inflater: LayoutInflater,
        tagsLayout: LinearLayout,
        noTagsView: TextView,
        tagGroups: Array<GalleryTagGroup>?,
        clickListener: View.OnClickListener,
        longClickListener: View.OnLongClickListener
    ) {
        tagsLayout.removeViews(1, tagsLayout.childCount - 1)
        if (tagGroups == null || tagGroups.isEmpty()) {
            noTagsView.visibility = View.VISIBLE
            return
        } else {
            noTagsView.visibility = View.GONE
        }

        ehTags = if (AppearanceSettings.getShowTagTranslations()) {
            EhTagDatabase.getInstance(context)
        } else {
            null
        }

        val colorTag = AttrResources.getAttrColor(context, R.attr.tagBackgroundColor)
        val colorName = AttrResources.getAttrColor(context, R.attr.tagGroupBackgroundColor)

        for (tg in tagGroups) {
            val ll = inflater.inflate(R.layout.gallery_tag_group, tagsLayout, false) as LinearLayout
            ll.orientation = LinearLayout.HORIZONTAL
            tagsLayout.addView(ll)

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
     * Show the tag long-press dialog with options (open definition, copy, etc.).
     */
    fun showTagDialog(baseScene: BaseScene, context: Context, tag: String) {
        if (tagDialog == null) {
            tagDialog = GalleryListSceneDialog(baseScene)
        }
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(context)
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
}
