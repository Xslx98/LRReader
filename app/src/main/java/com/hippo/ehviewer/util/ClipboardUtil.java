package com.hippo.ehviewer.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser;
import com.hippo.ehviewer.dao.LocalFavoriteInfo;
import com.hippo.ehviewer.ui.scene.ProgressScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.scene.Announcer;
import com.hippo.util.ExceptionUtils;

import org.json.JSONObject;


public class ClipboardUtil {

    private static final JSONObject defaultInfo;

    static {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("favoriteName", JSONObject.NULL);
            jsonObject.put("favoriteSlot", -2);
            jsonObject.put("pages", 0);
            jsonObject.put("rated", false);
            jsonObject.put("simpleTags", JSONObject.NULL);
            jsonObject.put("thumbWidth", 0);
            jsonObject.put("thumbHeight", 0);
            jsonObject.put("spanSize", 0);
            jsonObject.put("spanIndex", 0);
            jsonObject.put("spanGroupIndex", 0);
            defaultInfo = jsonObject;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 实现文本复制功能
     *
     * @param galleryInfo 复制的对象
     */
    public static void copy(GalleryInfo galleryInfo) {
        //对象转换
        String content = reduceString(galleryInfo);

        clearClipboard();
        if (!TextUtils.isEmpty(content)) {
            // 得到剪贴板管理器
            ClipboardManager cmb = (ClipboardManager) ServiceRegistry.INSTANCE.getAppModule().getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cmb.setText(content.trim());
            // 创建一个剪贴数据集，包含一个普通文本数据条目（需要复制的数据）
            ClipData clipData = ClipData.newPlainText(null, content);
            // 把数据集设置（复制）到剪贴板
            cmb.setPrimaryClip(clipData);
        }
    }

    /**
     * 实现文本复制功能
     *
     * @param text 复制的文本
     */
    public static void copyText(String text) {

        clearClipboard();
        if (!TextUtils.isEmpty(text)) {
            // 得到剪贴板管理器
            ClipboardManager cmb = (ClipboardManager) ServiceRegistry.INSTANCE.getAppModule().getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建一个剪贴数据集，包含一个普通文本数据条目（需要复制的数据）
            ClipData clipData = ClipData.newPlainText(null, text);
            // 把数据集设置（复制）到剪贴板
            cmb.setPrimaryClip(clipData);
        }
    }


    /**
     * 从剪切板获取数据
     * @return
     */
    public static GalleryInfo getGalleryInfoFromClip() {

        String compressString = getClipContent();

        String galleryString = GZIPUtils.uncompress(compressString);


        clearClipboard();
        try {
            JSONObject object = new JSONObject(galleryString);

            // Merge default values
            java.util.Iterator<String> keys = defaultInfo.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!object.has(key)) {
                    object.put(key, defaultInfo.get(key));
                }
            }
            object.put("time", System.currentTimeMillis());
            return GalleryInfo.galleryInfoFromJson(object);
        } catch (Exception e) {
            return null;
        }
    }

    private static String reduceString(GalleryInfo galleryInfo){
        JSONObject favoriteJson = galleryInfo.toJson();

        favoriteJson.remove("favoriteName");
        favoriteJson.remove("pages");
        favoriteJson.remove("rated");
        favoriteJson.remove("spanGroupIndex");
        favoriteJson.remove("spanIndex");
        favoriteJson.remove("spanSize");
        favoriteJson.remove("thumbHeight");
        favoriteJson.remove("thumbWidth");
        favoriteJson.remove("time");
        favoriteJson.remove("favoriteSlot");

        String s = favoriteJson.toString();

        return GZIPUtils.compress(s);
    }

    /**
     * 清空剪贴板内容
     */
    private static void clearClipboard() {
        ClipboardManager manager = (ClipboardManager) ServiceRegistry.INSTANCE.getAppModule().getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            try {
                manager.setPrimaryClip(manager.getPrimaryClip());
                manager.setText(null);
            } catch (Exception e) {
                ExceptionUtils.getReadableString(e);
            }
        }
    }
    /**
     * 获取系统剪贴板内容
     */
    private static String getClipContent() {

        ClipboardManager manager = (ClipboardManager) ServiceRegistry.INSTANCE.getAppModule().getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            if (manager.hasPrimaryClip() && manager.getPrimaryClip().getItemCount() > 0) {
                CharSequence addedText = manager.getPrimaryClip().getItemAt(0).getText();
                String addedTextString = String.valueOf(addedText);
                if (!TextUtils.isEmpty(addedTextString)) {
                    return addedTextString;
                }
            }
        }
        return "";
    }

    @Nullable
    public static Announcer createAnnouncerFromClipboardUrl(String url) {
        GalleryDetailUrlParser.Result result1 = GalleryDetailUrlParser.parse(url, false);
        if (result1 != null) {
            Bundle args = new Bundle();
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN);
            args.putLong(GalleryDetailScene.KEY_GID, result1.gid);
            args.putString(GalleryDetailScene.KEY_TOKEN, result1.token);
            return new Announcer(GalleryDetailScene.class).setArgs(args);
        }

        GalleryPageUrlParser.Result result2 = GalleryPageUrlParser.parse(url, false);
        if (result2 != null) {
            Bundle args = new Bundle();
            args.putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN);
            args.putLong(ProgressScene.KEY_GID, result2.gid);
            args.putString(ProgressScene.KEY_PTOKEN, result2.pToken);
            args.putInt(ProgressScene.KEY_PAGE, result2.page);
            return new Announcer(ProgressScene.class).setArgs(args);
        }

        return null;
    }
}
