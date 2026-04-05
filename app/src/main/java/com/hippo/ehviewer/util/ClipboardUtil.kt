package com.hippo.ehviewer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.Announcer
import com.hippo.util.ExceptionUtils
import org.json.JSONObject

object ClipboardUtil {

    private val defaultInfo: JSONObject = try {
        JSONObject().apply {
            put("favoriteName", JSONObject.NULL)
            put("favoriteSlot", -2)
            put("pages", 0)
            put("rated", false)
            put("simpleTags", JSONObject.NULL)
            put("thumbWidth", 0)
            put("thumbHeight", 0)
            put("spanSize", 0)
            put("spanIndex", 0)
            put("spanGroupIndex", 0)
        }
    } catch (e: Exception) {
        throw RuntimeException(e)
    }

    /**
     * 实现文本复制功能
     *
     * @param galleryInfo 复制的对象
     */
    @JvmStatic
    fun copy(galleryInfo: GalleryInfo) {
        //对象转换
        val content = reduceString(galleryInfo)

        clearClipboard()
        if (!TextUtils.isEmpty(content)) {
            // 得到剪贴板管理器
            val cmb = ServiceRegistry.appModule.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            @Suppress("DEPRECATION")
            cmb.text = content?.trim()
            // 创建一个剪贴数据集，包含一个普通文本数据条目（需要复制的数据）
            val clipData = ClipData.newPlainText(null, content)
            // 把数据集设置（复制）到剪贴板
            cmb.setPrimaryClip(clipData)
        }
    }

    /**
     * 实现文本复制功能
     *
     * @param text 复制的文本
     */
    @JvmStatic
    fun copyText(text: String?) {
        clearClipboard()
        if (!TextUtils.isEmpty(text)) {
            // 得到剪贴板管理器
            val cmb = ServiceRegistry.appModule.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // 创建一个剪贴数据集，包含一个普通文本数据条目（需要复制的数据）
            val clipData = ClipData.newPlainText(null, text)
            // 把数据集设置（复制）到剪贴板
            cmb.setPrimaryClip(clipData)
        }
    }

    /**
     * 从剪切板获取数据
     */
    @JvmStatic
    fun getGalleryInfoFromClip(): GalleryInfo? {
        val compressString = getClipContent()
        val galleryString = GZIPUtils.uncompress(compressString)

        clearClipboard()
        return try {
            val obj = JSONObject(galleryString!!)

            // Merge default values
            val keys = defaultInfo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!obj.has(key)) {
                    obj.put(key, defaultInfo.get(key))
                }
            }
            obj.put("time", System.currentTimeMillis())
            GalleryInfo.galleryInfoFromJson(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun reduceString(galleryInfo: GalleryInfo): String? {
        val favoriteJson = galleryInfo.toJson()

        favoriteJson.remove("favoriteName")
        favoriteJson.remove("pages")
        favoriteJson.remove("rated")
        favoriteJson.remove("spanGroupIndex")
        favoriteJson.remove("spanIndex")
        favoriteJson.remove("spanSize")
        favoriteJson.remove("thumbHeight")
        favoriteJson.remove("thumbWidth")
        favoriteJson.remove("time")
        favoriteJson.remove("favoriteSlot")

        val s = favoriteJson.toString()
        return GZIPUtils.compress(s)
    }

    /**
     * 清空剪贴板内容
     */
    private fun clearClipboard() {
        val manager = ServiceRegistry.appModule.getContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager != null) {
            try {
                manager.setPrimaryClip(manager.primaryClip!!)
                @Suppress("DEPRECATION")
                manager.text = null
            } catch (e: Exception) {
                ExceptionUtils.getReadableString(e)
            }
        }
    }

    /**
     * 获取系统剪贴板内容
     */
    private fun getClipContent(): String {
        val manager = ServiceRegistry.appModule.getContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager != null) {
            if (manager.hasPrimaryClip() && manager.primaryClip!!.itemCount > 0) {
                val addedText = manager.primaryClip!!.getItemAt(0).text
                val addedTextString = addedText.toString()
                if (!TextUtils.isEmpty(addedTextString)) {
                    return addedTextString
                }
            }
        }
        return ""
    }

    @JvmStatic
    fun createAnnouncerFromClipboardUrl(url: String?): Announcer? {
        val result1 = GalleryDetailUrlParser.parse(url, false)
        if (result1 != null) {
            val args = Bundle().apply {
                putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
                putLong(GalleryDetailScene.KEY_GID, result1.gid)
                putString(GalleryDetailScene.KEY_TOKEN, result1.token)
            }
            return Announcer(GalleryDetailScene::class.java).setArgs(args)
        }

        val result2 = GalleryPageUrlParser.parse(url, false)
        if (result2 != null) {
            val args = Bundle().apply {
                putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN)
                putLong(ProgressScene.KEY_GID, result2.gid)
                putString(ProgressScene.KEY_PTOKEN, result2.pToken)
                putInt(ProgressScene.KEY_PAGE, result2.page)
            }
            return Announcer(ProgressScene::class.java).setArgs(args)
        }

        return null
    }
}
