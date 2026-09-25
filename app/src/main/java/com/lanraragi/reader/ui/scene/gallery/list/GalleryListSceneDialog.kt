package com.lanraragi.reader.ui.scene.gallery.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.client.TagTranslationDatabase
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.util.TagTranslationUtil

class GalleryListSceneDialog(val baseScene: BaseScene) {
    val context: Context? = baseScene.context
    private var tagName: String? = null

    fun setTagName(tagName: String?) {
        this.tagName = tagName
    }

    fun showTagLongPressDialog(ehTags: TagTranslationDatabase?) {
        val title = if (AppearanceSettings.getShowTagTranslations()) {
            TagTranslationUtil.getTagCN(tagName, ehTags) + "(" + tagName + ")"
        } else {
            tagName
        }
        AlertDialog.Builder(context!!)
            .setTitle(title)
            .setNegativeButton(R.string.copy_tag) { _: DialogInterface?, _: Int -> copyTag(tagName) }
            .show()
    }

    private fun copyTag(tag: String?) {
        val manager = context!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(null, tag))
        Toast.makeText(context, R.string.gallery_tag_copy, Toast.LENGTH_LONG).show()
    }
}
