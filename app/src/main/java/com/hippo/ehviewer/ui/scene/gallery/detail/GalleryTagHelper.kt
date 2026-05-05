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
import com.hippo.ehviewer.settings.AppearanceSettings
import com.lanraragi.reader.domain.TagGroup
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

    fun getEhTags(): EhTagDatabase? = ehTags

    /**
     * Populate the tags LinearLayout with tag group chips.
     */
    fun bindTags(
        context: Context,
        inflater: LayoutInflater,
        tagsLayout: LinearLayout,
        noTagsView: TextView,
        tagGroups: List<TagGroup>?,
        clickListener: View.OnClickListener,
        longClickListener: View.OnLongClickListener
    ) {
        tagsLayout.removeViews(1, tagsLayout.childCount - 1)
        if (tagGroups.isNullOrEmpty()) {
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

            val readableTagName = ehTags?.getTranslation("n:" + tg.namespace)

            val tgName = inflater.inflate(R.layout.item_gallery_tag, ll, false) as TextView
            ll.addView(tgName)
            tgName.text = readableTagName ?: tg.namespace
            tgName.background = RoundSideRectDrawable(colorName)

            val prefix = EhTagDatabase.namespaceToPrefix(tg.namespace) ?: ""

            val awl = AutoWrapLayout(context)
            ll.addView(awl, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            for (tagStr in tg.tags) {
                val tag = inflater.inflate(R.layout.item_gallery_tag, awl, false) as TextView
                awl.addView(tag)

                val readableTag = ehTags?.getTranslation(prefix + tagStr)

                tag.text = readableTag ?: tagStr
                tag.background = RoundSideRectDrawable(colorTag)
                tag.setTag(R.id.tag, tg.namespace + ":" + tagStr)
                tag.setOnClickListener(clickListener)
                tag.setOnLongClickListener(longClickListener)
            }
        }
    }

    /**
     * Show the tag long-press dialog with options (open definition, copy, etc.).
     */
    fun showTagDialog(baseScene: BaseScene, context: Context, tag: String) {
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(context)
        }
        val dialog = GalleryListSceneDialog(baseScene)
        dialog.setTagName(tag)
        dialog.showTagLongPressDialog(ehTags)
    }

    /**
     * Clean up references (call from onDestroyView).
     */
    fun destroy() {
        ehTags = null
    }
}
