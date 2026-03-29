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

package com.hippo.ehviewer;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.settings.AppearanceSettings;
import com.hippo.ehviewer.settings.NetworkSettings;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.NumberUtils;

import java.io.File;
import java.util.Date;
import java.util.Locale;

public class Settings {

    private static final String TAG = Settings.class.getSimpleName();

    @SuppressLint("StaticFieldLeak") // Safe: holds Application Context, not Activity
    private static Context sContext;
    private static SharedPreferences sSettingsPre;
    private static SharedPreferences sArchiverPre;

    public static void initialize(Context context) {
        sContext = context.getApplicationContext();
        sSettingsPre = PreferenceManager.getDefaultSharedPreferences(sContext);
        sArchiverPre = context.getSharedPreferences("archiver_cache",Context.MODE_PRIVATE);
        if (AppearanceSettings.getDarkModeStatus(context) && AppearanceSettings.isThemeAutoSwitchAvailable()) {
            AppearanceSettings.putTheme(AppearanceSettings.THEME_DARK);
        }

        fixDefaultValue();
    }

    /** Exposed for modular settings objects. */
    public static Context getContext() { return sContext; }

    /** Exposed for modular settings objects that need batch editor access. */
    public static SharedPreferences getPreferences() { return sSettingsPre; }



    private static void fixDefaultValue() {
        // Enable builtin hosts if the country is CN
        if (!sSettingsPre.contains(NetworkSettings.KEY_BUILT_IN_HOSTS)) {
            if ("CN".equals(Locale.getDefault().getCountry())) {
                NetworkSettings.putBuiltInHosts(true);
                NetworkSettings.putBuiltEXHosts(true);
            }
        }
        if (!sSettingsPre.contains(NetworkSettings.KEY_DOMAIN_FRONTING)) {
            if ("CN".equals(Locale.getDefault().getCountry())) {
                NetworkSettings.putDF(true);
            }
        }

    }


    public static GalleryInfo getArchiverDownload(long downloadId){
        String s = sArchiverPre.getString(String.valueOf(downloadId),"");
        if (s.isEmpty()){
            return null;
        }
        try {
            return GalleryInfo.galleryInfoFromJson(new org.json.JSONObject(s));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    public static void putArchiverDownload(long downloadId,GalleryInfo info){
        sArchiverPre.edit().putString(String.valueOf(downloadId),info.toJson().toString()).apply();
    }

    public static boolean deleteArchiverDownload(long downloadId){
        return sArchiverPre.edit().remove(String.valueOf(downloadId)).commit();
    }

    public static long getArchiverDownloadId(long gid){
        return sArchiverPre.getLong(gid+"DId",-1L);
    }

    public static void putArchiverDownloadId(long gid,long downloadId){
        sArchiverPre.edit().putLong(gid+"DId",downloadId).apply();
    }

    public static boolean deleteArchiverDownloadId(long gid){
        return sArchiverPre.edit().remove(gid+"DId").commit();
    }

    public static boolean getBoolean(String key, boolean defValue) {
        try {
            return sSettingsPre.getBoolean(key, defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putBoolean(String key, boolean value) {
        sSettingsPre.edit().putBoolean(key, value).apply();
    }

    public static int getInt(String key, int defValue) {
        try {
            return sSettingsPre.getInt(key, defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putInt(String key, int value) {
        sSettingsPre.edit().putInt(key, value).apply();
    }

    public static long getLong(String key, long defValue) {
        try {
            return sSettingsPre.getLong(key, defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putLong(String key, long value) {
        sSettingsPre.edit().putLong(key, value).apply();
    }

    public static float getFloat(String key, float defValue) {
        try {
            return sSettingsPre.getFloat(key, defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putFloat(String key, float value) {
        sSettingsPre.edit().putFloat(key, value).apply();
    }

    public static String getString(String key, String defValue) {
        try {
            return sSettingsPre.getString(key, defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putString(String key, String value) {
        sSettingsPre.edit().putString(key, value).apply();
    }

    public static int getIntFromStr(String key, int defValue) {
        try {
            return NumberUtils.parseIntSafely(sSettingsPre.getString(key, Integer.toString(defValue)), defValue);
        } catch (ClassCastException e) {
            Log.d(TAG, "Get ClassCastException when get " + key + " value", e);
            return defValue;
        }
    }

    public static void putIntToStr(String key, int value) {
        sSettingsPre.edit().putString(key, Integer.toString(value)).apply();
    }

    private static final String KEY_VERSION_CODE = "version_code";
    private static final int DEFAULT_VERSION_CODE = 0;

    public static int getVersionCode() {
        return getInt(KEY_VERSION_CODE, DEFAULT_VERSION_CODE);
    }

    public static void putVersionCode(int value) {
        putInt(KEY_VERSION_CODE, value);
    }

    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String DEFAULT_DISPLAY_NAME = null;

    public static String getDisplayName() {
        return getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME);
    }

    public static void putDisplayName(String value) {
        putString(KEY_DISPLAY_NAME, value);
    }

    private static final String KEY_AVATAR = "avatar";
    private static final String DEFAULT_AVATAR = null;

    public static String getAvatar() {
        return getString(KEY_AVATAR, DEFAULT_AVATAR);
    }

    public static void putAvatar(String value) {
        putString(KEY_AVATAR, value);
    }

    private static final String KEY_SHOW_WARNING = "show_warning";
    private static final boolean DEFAULT_SHOW_WARNING = true;

    public static boolean getShowWarning() {
        return getBoolean(KEY_SHOW_WARNING, DEFAULT_SHOW_WARNING);
    }

    public static void putShowWarning(boolean value) {
        putBoolean(KEY_SHOW_WARNING, value);
    }

    private static final String KEY_REMOVE_IMAGE_FILES = "include_pic";
    private static final boolean DEFAULT_REMOVE_IMAGE_FILES = true;

    public static boolean getRemoveImageFiles() {
        return getBoolean(KEY_REMOVE_IMAGE_FILES, DEFAULT_REMOVE_IMAGE_FILES);
    }

    public static void putRemoveImageFiles(boolean value) {
        putBoolean(KEY_REMOVE_IMAGE_FILES, value);
    }


    private static final String KEY_QUICK_SEARCH_TIP = "quick_search_tip";
    private static final boolean DEFAULT_QUICK_SEARCH_TIP = true;

    public static boolean getQuickSearchTip() {
        return getBoolean(KEY_QUICK_SEARCH_TIP, DEFAULT_QUICK_SEARCH_TIP);
    }

    public static void putQuickSearchTip(boolean value) {
        putBoolean(KEY_QUICK_SEARCH_TIP, value);
    }

    /********************
     ****** Eh
     ********************/
    // Theme, appearance, gallery site → AppearanceSettings.kt

    // Launch page, list mode, thumb size, tag translations, categories,
    // excluded languages, cellular warning → AppearanceSettings.kt / NetworkSettings.kt

    // Excluded tag namespaces and languages are still called directly from
    // ExcludedLanguagesActivity and ExcludedTagNamespacesPreference.
    // TODO: migrate these callers to a Kotlin settings object, then remove.
    public static final String KEY_EXCLUDED_TAG_NAMESPACES = "excluded_tag_namespaces";
    private static final int DEFAULT_EXCLUDED_TAG_NAMESPACES = 0;

    public static int getExcludedTagNamespaces() {
        return getInt(KEY_EXCLUDED_TAG_NAMESPACES, DEFAULT_EXCLUDED_TAG_NAMESPACES);
    }

    public static void putExcludedTagNamespaces(int value) {
        putInt(KEY_EXCLUDED_TAG_NAMESPACES, value);
    }

    public static final String KEY_EXCLUDED_LANGUAGES = "excluded_languages";
    private static final String DEFAULT_EXCLUDED_LANGUAGES = null;

    public static String getExcludedLanguages() {
        return getString(KEY_EXCLUDED_LANGUAGES, DEFAULT_EXCLUDED_LANGUAGES);
    }

    public static void putExcludedLanguages(String value) {
        putString(KEY_EXCLUDED_LANGUAGES, value);
    }

    // Reading → ReadingSettings.kt
    // Security → SecuritySettings.kt
    // Download location, labels, preload → DownloadSettings.kt
    // Favorites → FavoritesSettings.kt

    /********************
     ****** Analytics
     ********************/
    private static final String KEY_ASK_ANALYTICS = "ask_analytics";
    private static final boolean DEFAULT_ASK_ANALYTICS = true;

    public static boolean getAskAnalytics() {
        return getBoolean(KEY_ASK_ANALYTICS, DEFAULT_ASK_ANALYTICS);
    }

    public static void putAskAnalytics(boolean value) {
        putBoolean(KEY_ASK_ANALYTICS, value);
    }

    public static final String KEY_ENABLE_ANALYTICS = "enable_analytics";
    private static final boolean DEFAULT_ENABLE_ANALYTICS = false;

    public static boolean getEnableAnalytics() {
        return getBoolean(KEY_ENABLE_ANALYTICS, DEFAULT_ENABLE_ANALYTICS);
    }

    public static void putEnableAnalytics(boolean value) {
        putBoolean(KEY_ENABLE_ANALYTICS, value);
    }

    private static final String KEY_USER_ID = "user_id";
    private static final String FILENAME_USER_ID = ".user_id";
    private static final int LENGTH_USER_ID = 32;

    public static String getUserID() {
        boolean writeXml = false;
        boolean writeFile = false;
        String userID = getString(KEY_USER_ID, null);
        File file = AppConfig.getFileInExternalAppDir(FILENAME_USER_ID);
        if (!isValidUserID(userID)) {
            writeXml = true;
            // Get use ID from out sd card file
            userID = FileUtils.read(file);
            if (!isValidUserID(userID)) {
                writeFile = true;
                userID = generateUserID();
            }
        } else {
            writeFile = true;
        }

        if (writeXml) {
            putString(KEY_USER_ID, userID);
        }
        if (writeFile) {
            FileUtils.write(file, userID);
        }

        return userID;
    }

    @NonNull
    private static String generateUserID() {
        int length = LENGTH_USER_ID;
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            if (MathUtils.random(0, ('9' - '0' + 1) + ('z' - 'a' + 1)) <= '9' - '0') {
                sb.append((char) MathUtils.random('0', '9' + 1));
            } else {
                sb.append((char) MathUtils.random('a', 'z' + 1));
            }
        }

        return sb.toString();
    }

    private static boolean isValidUserID(@Nullable String userID) {
        if (null == userID || LENGTH_USER_ID != userID.length()) {
            return false;
        }

        for (int i = 0; i < LENGTH_USER_ID; i++) {
            char ch = userID.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'z')) {
                return false;
            }
        }

        return true;
    }

    /********************
     ****** Update
     ********************/
    private static final String KEY_BETA_UPDATE_CHANNEL = "beta_update_channel";
    private static final boolean DEFAULT_BETA_UPDATE_CHANNEL = EhApplication.BETA;
    private static final String KEY_SKIP_UPDATE_VERSION = "skip_update_version";
    private static final int DEFAULT_SKIP_UPDATE_VERSION = 0;

    public static boolean getBetaUpdateChannel() {
        return getBoolean(KEY_BETA_UPDATE_CHANNEL, DEFAULT_BETA_UPDATE_CHANNEL);
    }

    public static void putBetaUpdateChannel(boolean value) {
        putBoolean(KEY_BETA_UPDATE_CHANNEL, value);
    }

    public static int getSkipUpdateVersion() {
        return getInt(KEY_SKIP_UPDATE_VERSION, DEFAULT_SKIP_UPDATE_VERSION);
    }

    public static void putSkipUpdateVersion(int value) {
        putInt(KEY_SKIP_UPDATE_VERSION, value);
    }

    /********************
     ****** Advanced
     ********************/
    public static final String KEY_SAVE_PARSE_ERROR_BODY = "save_parse_error_body";
    private static final boolean DEFAULT_SAVE_PARSE_ERROR_BODY = EhApplication.BETA;

    public static boolean getSaveParseErrorBody() {
        return getBoolean(KEY_SAVE_PARSE_ERROR_BODY, DEFAULT_SAVE_PARSE_ERROR_BODY);
    }

    public static void putSaveParseErrorBody(boolean value) {
        putBoolean(KEY_SAVE_PARSE_ERROR_BODY, value);
    }

    private static final String KEY_SAVE_CRASH_LOG = "save_crash_log";
    private static final boolean DEFAULT_SAVE_CRASH_LOG = false;

    public static boolean getSaveCrashLog() {
        return getBoolean(KEY_SAVE_CRASH_LOG, DEFAULT_SAVE_CRASH_LOG);
    }

    // Security (password, fingerprint) → SecuritySettings.kt
    // Read cache size → ReadingSettings.kt
    // Network (hosts, language, proxy) → NetworkSettings.kt / AppearanceSettings.kt

    /********************
     ****** Guide
     ********************/
    private static final String KEY_GUIDE_QUICK_SEARCH = "guide_quick_search";
    private static final boolean DEFAULT_GUIDE_QUICK_SEARCH = true;

    public static boolean getGuideQuickSearch() {
        return getBoolean(KEY_GUIDE_QUICK_SEARCH, DEFAULT_GUIDE_QUICK_SEARCH);
    }

    public static void putGuideQuickSearch(boolean value) {
        putBoolean(KEY_GUIDE_QUICK_SEARCH, value);
    }

    private static final String KEY_GUIDE_COLLECTIONS = "guide_collections";
    private static final boolean DEFAULT_GUIDE_COLLECTIONS = true;

    public static boolean getGuideCollections() {
        return getBoolean(KEY_GUIDE_COLLECTIONS, DEFAULT_GUIDE_COLLECTIONS);
    }

    public static void putGuideCollections(boolean value) {
        putBoolean(KEY_GUIDE_COLLECTIONS, value);
    }

    private static final String KEY_GUIDE_DOWNLOAD_THUMB = "guide_download_thumb";
    private static final boolean DEFAULT_GUIDE_DOWNLOAD_THUMB = true;

    public static boolean getGuideDownloadThumb() {
        return getBoolean(KEY_GUIDE_DOWNLOAD_THUMB, DEFAULT_GUIDE_DOWNLOAD_THUMB);
    }

    public static void putGuideDownloadThumb(boolean value) {
        putBoolean(KEY_GUIDE_DOWNLOAD_THUMB, value);
    }

    private static final String KEY_GUIDE_DOWNLOAD_LABELS = "guide_download_labels";
    private static final boolean DEFAULT_GUIDE_DOWNLOAD_LABELS = true;

    public static boolean getGuideDownloadLabels() {
        return getBoolean(KEY_GUIDE_DOWNLOAD_LABELS, DEFAULT_GUIDE_DOWNLOAD_LABELS);
    }

    public static void puttGuideDownloadLabels(boolean value) {
        putBoolean(KEY_GUIDE_DOWNLOAD_LABELS, value);
    }

    private static final String KEY_GUIDE_GALLERY = "guide_gallery";
    private static final boolean DEFAULT_GUIDE_GALLERY = true;

    public static boolean getGuideGallery() {
        return getBoolean(KEY_GUIDE_GALLERY, DEFAULT_GUIDE_GALLERY);
    }

    public static void putGuideGallery(boolean value) {
        putBoolean(KEY_GUIDE_GALLERY, value);
    }

    private static final String KEY_CLIPBOARD_TEXT_HASH_CODE = "clipboard_text_hash_code";
    private static final int DEFAULT_CLIPBOARD_TEXT_HASH_CODE = 0;

    public static int getClipboardTextHashCode() {
        return getInt(KEY_CLIPBOARD_TEXT_HASH_CODE, DEFAULT_CLIPBOARD_TEXT_HASH_CODE);
    }

    public static void putClipboardTextHashCode(int value) {
        putInt(KEY_CLIPBOARD_TEXT_HASH_CODE, value);
    }


    // DNS-over-HTTPS, domain fronting → NetworkSettings.kt
    // Download delay → DownloadSettings.kt

    private static final String KEY_IS_LOGIN = "is_login";

    private static boolean IS_LOGIN = false;

    public static boolean isLogin() {
        return getBoolean(KEY_IS_LOGIN, IS_LOGIN);
    }

    public static void setLoginState(boolean value) {
        putBoolean(KEY_IS_LOGIN, value);
    }


    // Gallery comment, rating display → AppearanceSettings.kt

    public static final String KEY_CLOSE_AUTO_UPDATES = "close_auto_updates";

    private static boolean IS_CLOSE_AUTO_UPDATES = false;

    public static boolean getCloseAutoUpdate() {
        return getBoolean(KEY_CLOSE_AUTO_UPDATES, IS_CLOSE_AUTO_UPDATES);
    }

    public static void setKeyCloseAutoUpdates(boolean value) {
        putBoolean(KEY_CLOSE_AUTO_UPDATES, value);
    }

    public static final String KEY_SHOW_EH_EVENTS = "show_eh_events";

    private static boolean IS_SHOW_EH_EVENTS = true;

    public static boolean getShowEhEvents() {
        return getBoolean(KEY_SHOW_EH_EVENTS, IS_SHOW_EH_EVENTS) && isLogin();
    }

    public static void setKeyShowEhEvents(boolean value) {
        putBoolean(KEY_SHOW_EH_EVENTS, value);
    }

    public static final String KEY_SHOW_EH_LIMITS = "show_eh_limits";

    private static boolean IS_SHOW_EH_LIMITS = true;

    public static boolean getShowEhLimits() {
        return getBoolean(KEY_SHOW_EH_LIMITS, IS_SHOW_EH_LIMITS) && isLogin();
    }

    public static void setKeyShowEhLimits(boolean value) {
        putBoolean(KEY_SHOW_EH_LIMITS, value);
    }


    // Note: USER_BACKGROUND_IMAGE/USER_AVATAR_IMAGE are still used directly
    public static final String USER_BACKGROUND_IMAGE = "background_image_path";
    public static final String USER_AVATAR_IMAGE = "avatar_image_path";

    public static File getUserImageFile(String key){
        String path = getString(key,"");
        if (path.isEmpty()){
            return null;
        }
        File file = new File(path);
        if (file.exists()){
            return file;
        }else {
            return null;
        }
    }

    public static void saveFilePath(String key,String path){
        putString(key,path);
    }

    // Download order, pagination, drag, timeout → DownloadSettings.kt
    // Read progress display → ReadingSettings.kt
    // History info size → AppearanceSettings.kt

    public static final String KEY_LAST_UPDATE_TIME = "last_update_time";

    public static long DEFAULT_LAST_UPDATE_TIME = 0L;

    public static boolean getIsUpdateTime() {
        long lastUpdateTime = getLong(KEY_LAST_UPDATE_TIME, DEFAULT_LAST_UPDATE_TIME);
        Date now = new Date();
        long nowTime = now.getTime();
        long msNum = nowTime - lastUpdateTime;
        long dayNum = msNum / (1000 * 60 * 60 * 24);
        return dayNum >= 1;
    }

    public static void putUpdateTime(long updateTime) {
        putLong(KEY_LAST_UPDATE_TIME,updateTime);
    }
}
