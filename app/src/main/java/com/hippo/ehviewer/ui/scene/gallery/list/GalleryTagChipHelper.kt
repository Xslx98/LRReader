package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.util.TagTranslationUtil
import com.hippo.widget.LoadImageViewNew

/**
 * Handles tag chip building, popup window, and tag click/long-click for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryTagChipHelper(private val callback: Callback) {

    interface Callback {
        fun getHostContext(): Context?
        fun requireContext(): Context
        fun getLayoutInflater(): LayoutInflater
        fun isDrawersVisible(): Boolean
        fun closeDrawer(gravity: Int)
        fun getUrlBuilder(): ListUrlBuilder?
        fun getContentHelper(): com.hippo.widget.ContentLayout.ContentHelper<*>?
        fun isFilterOpen(): Boolean
        fun buildFilterSearch(tagName: String): String
        fun updateFilterDisplay()
        fun onUpdateUrlBuilder()
        fun setState(state: Int)
        fun onItemClick(view: View?, gi: GalleryInfo?): Boolean
        fun onItemLongClick(gi: GalleryInfo?, view: View): Boolean
        fun dismissItemDialog()
        fun getBaseScene(): BaseScene
    }

    private var popupWindow: PopupWindow? = null
    private var popupWindowPosition = -1
    private var tagDialog: GalleryListSceneDialog? = null
    private var ehTags: EhTagDatabase? = null

    fun setEhTags(tags: EhTagDatabase?) {
        ehTags = tags
    }

    fun getEhTags(): EhTagDatabase? = ehTags

    fun dismissPopup() {
        popupWindow?.dismiss()
        popupWindowPosition = -1
    }

    fun onThumbItemClick(position: Int, view: View, gi: GalleryInfo?) {
        val thumb: LoadImageViewNew = view.findViewById(R.id.thumb_new)
        if (thumb.mFailed) {
            thumb.load()
            return
        }

        if (popupWindow != null) {
            if (popupWindowPosition == position) {
                popupWindowPosition = -1
                popupWindow!!.dismiss()
                return
            }
            popupWindowPosition = -1
            popupWindow!!.dismiss()
        }

        val tgList = gi?.tgList
        if (gi != null && (tgList == null || tgList.isEmpty())) {
            callback.onItemClick(view, gi)
            return
        }

        if (position != popupWindowPosition) {
            @SuppressLint("InflateParams")
            val popView = callback.getLayoutInflater()
                .inflate(R.layout.list_thumb_popupwindow, null) as LinearLayout
            val tagFlowLayout = buildChipGroup(gi, popView.findViewById(R.id.tab_tag_flow))

            popupWindow = PopupWindow(popView, view.width - thumb.width, thumb.height)
            popupWindow!!.isOutsideTouchable = true
            popupWindow!!.animationStyle = R.style.PopupWindow

            tagFlowLayout.setOnClickListener {
                popupWindowPosition = -1
                popupWindow!!.dismiss()
                callback.onItemClick(view, gi)
            }
            tagFlowLayout.setOnLongClickListener { callback.onItemLongClick(gi, view) }
            val location = IntArray(2)
            thumb.getLocationOnScreen(location)
            popupWindow!!.showAtLocation(thumb, Gravity.NO_GRAVITY, location[0] + thumb.width, location[1])
            popupWindowPosition = position
        }
    }

    fun buildChipGroup(gi: GalleryInfo?, tagFlowLayout: ChipGroup): ChipGroup {
        val colorTag = AttrResources.getAttrColor(callback.requireContext(), R.attr.tagBackgroundColor)
        val tgList = gi?.tgList
        if (tgList == null) {
            val tagName = callback.requireContext().getString(R.string.lrr_no_preview_tags)
            @SuppressLint("InflateParams")
            val chip = callback.getLayoutInflater().inflate(R.layout.item_chip_tag, null) as Chip
            chip.chipBackgroundColor = ColorStateList.valueOf(colorTag)
            chip.setTextColor(Color.WHITE)
            if (AppearanceSettings.getShowTagTranslations()) {
                ensureEhTags()
                chip.text = TagTranslationUtil.getTagCNBody(
                    tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray(), ehTags
                )
            } else {
                val tagSplit = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                chip.text = if (tagSplit.size > 1) tagSplit[1] else tagSplit[0]
            }
            tagFlowLayout.addView(chip, 0)
            return tagFlowLayout
        }
        for (i in tgList.indices) {
            val tagName = tgList[i]
            @SuppressLint("InflateParams")
            val chip = callback.getLayoutInflater().inflate(R.layout.item_chip_tag, null) as Chip
            chip.chipBackgroundColor = ColorStateList.valueOf(colorTag)
            chip.setTextColor(Color.WHITE)
            if (AppearanceSettings.getShowTagTranslations()) {
                ensureEhTags()
                chip.text = TagTranslationUtil.getTagCNBody(
                    tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray(), ehTags
                )
            } else {
                val tagSplit = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                chip.text = if (tagSplit.size > 1) tagSplit[1] else tagSplit[0]
            }
            chip.setOnClickListener { onTagClick(tagName) }
            chip.setOnLongClickListener { onTagLongClick(tagName) }
            tagFlowLayout.addView(chip, i)
        }

        return tagFlowLayout
    }

    fun onTagClick(tagName: String) {
        if (callback.isDrawersVisible()) {
            callback.closeDrawer(Gravity.RIGHT)
        }
        val helper = callback.getContentHelper()
        val urlBuilder = callback.getUrlBuilder()
        if (helper == null || urlBuilder == null) {
            return
        }
        popupWindowPosition = -1
        popupWindow?.dismiss()
        callback.dismissItemDialog()

        if (callback.isFilterOpen()) {
            urlBuilder.set(callback.buildFilterSearch(tagName), ListUrlBuilder.MODE_FILTER)
            callback.updateFilterDisplay()
        } else {
            urlBuilder.set(tagName)
        }

        urlBuilder.pageIndex = 0
        callback.onUpdateUrlBuilder()
        helper.refresh()
        callback.setState(GalleryStateHelper.STATE_NORMAL)
    }

    fun onTagLongClick(tagName: String): Boolean {
        if (tagDialog == null) {
            tagDialog = GalleryListSceneDialog(callback.getBaseScene())
        }
        ensureEhTags()
        tagDialog!!.setTagName(tagName)
        tagDialog!!.showTagLongPressDialog(ehTags)
        return true
    }

    private fun ensureEhTags() {
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(callback.requireContext())
        }
    }
}
