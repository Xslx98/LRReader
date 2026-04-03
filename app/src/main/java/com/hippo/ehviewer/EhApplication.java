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

import com.hippo.ehviewer.settings.DownloadSettings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import android.os.Debug;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.Native;
import com.hippo.a7zip.A7Zip;
import com.hippo.content.RecordingApplication;
import com.hippo.content.ContextLocalWrapper;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.lib.image.Image;

import com.hippo.unifile.UniFile;
import com.hippo.util.BitmapUtils;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.util.ReadableTime;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.SimpleHandler;

import android.text.Html;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import android.content.res.Resources;
import java.util.Locale;


public class EhApplication extends RecordingApplication {

    @Override
    protected void attachBaseContext(Context base) {
        // Apply locale before super.attachBaseContext() so the Application context
        // uses the correct language. Settings is not yet initialized here, so we
        // read the preference directly from SharedPreferences.
        Locale locale = null;
        try {
            android.content.SharedPreferences prefs =
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(base);
            String language = prefs.getString("app_language", "system");
            if (language != null && !language.equals("system")) {
                String[] split = language.split("-");
                if (split.length == 1) {
                    locale = new Locale(split[0]);
                } else if (split.length == 2) {
                    locale = new Locale(split[0], split[1]);
                } else if (split.length == 3) {
                    locale = new Locale(split[0], split[1], split[2]);
                }
            }
        } catch (Exception ignored) {
            // First launch or preference not yet available — use system locale
        }

        if (locale == null) {
            locale = Resources.getSystem().getConfiguration().locale;
        }
        base = ContextLocalWrapper.wrap(base, locale);
        super.attachBaseContext(base);
    }

    private static final String TAG = EhApplication.class.getSimpleName();

    public static final boolean BETA = false;

    private static final boolean DEBUG_PRINT_NATIVE_MEMORY = false;
    private static final boolean DEBUG_PRINT_IMAGE_COUNT = false;
    private static final long DEBUG_PRINT_INTERVAL = 3000L;

    private static EhApplication instance;

    private final List<Activity> mActivityList = new ArrayList<>();

    private boolean initialized = false;

    public static EhApplication getInstance() {
        return instance;
    }

    @SuppressLint("StaticFieldLeak") // Safe: Application instance is process-scoped
    @Override
    public void onCreate() {
        instance = this;

        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                // Always save crash file if onCreate() is not done
                if (!initialized || Settings.getSaveCrashLog()) {
                    Crash.saveCrashLog(instance, e);
                }
            } catch (Throwable ignored) {
            }

            if (handler != null) {
                handler.uncaughtException(t, e);
            }
        });

        super.onCreate();

        GetText.initialize(this);
        com.hippo.network.StatusCodeException.initialize(this);
        Settings.initialize(this);
        com.hippo.ehviewer.client.lrr.LRRAuthManager.initialize(this);
        ReadableTime.initialize(this);
        AppConfig.initialize(this);
        // Skip SpiderDen disk cache in LRR mode — it's EH-specific and wastes 40-640MB
        // SpiderDen.initialize(this);
        EhDB.initialize(this);

        // Load active server profile into LRRAuthManager
        try {
            com.hippo.ehviewer.dao.ServerProfile activeProfile = EhDB.getActiveProfile();
            if (activeProfile != null) {
                com.hippo.ehviewer.client.lrr.LRRAuthManager.setActiveProfileId(activeProfile.getId());
            }
        } catch (Exception e) {
            // DB not ready yet on first launch — safe to ignore
        }

        // One-time migration: move API keys from plaintext Room columns to EncryptedSharedPreferences
        if (!Settings.getBoolean("api_key_migration_done", false)) {
            try {
                java.util.List<com.hippo.ehviewer.dao.ServerProfile> profiles = EhDB.getAllServerProfiles();
                for (com.hippo.ehviewer.dao.ServerProfile profile : profiles) {
                    if (profile.getApiKey() != null && !profile.getApiKey().isEmpty()) {
                        com.hippo.ehviewer.client.lrr.LRRAuthManager.setApiKeyForProfile(
                                profile.getId(), profile.getApiKey());
                        EhDB.updateServerProfile(new com.hippo.ehviewer.dao.ServerProfile(
                                profile.getId(), profile.getName(), profile.getUrl(),
                                null, profile.isActive()));
                    }
                }
                Settings.putBoolean("api_key_migration_done", true);
            } catch (Exception e) {
                // Migration will retry on next launch
                android.util.Log.w("EhApplication", "API key migration failed, will retry", e);
            }
        }

        EhEngine.initialize();
        com.hippo.ehviewer.client.lrr.LRRClientProvider.init(this);
        BitmapUtils.initialize(this);
        Image.initialize(this);
        Native.initialize();
        A7Zip.initialize(this);
        if (EhDB.needMerge()) {
            EhDB.mergeOldDB(this);
        }

        // Initialize ServiceRegistry (must be after Settings/EhDB)
        ServiceRegistry.INSTANCE.initialize(this);

        if (Settings.getEnableAnalytics()) {
            Analytics.start(this);
        }

        // Do io tasks in background thread
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            // Check no media file
            try {
                UniFile downloadLocation = DownloadSettings.getDownloadLocation();
                if (DownloadSettings.getMediaScan()) {
                    CommonOperations.removeNoMediaFile(downloadLocation);
                } else {
                    CommonOperations.ensureNoMediaFile(downloadLocation);
                }
            } catch (Throwable t) {
                ExceptionUtils.throwIfFatal(t);
            }

            // Clear temp files
            try {
                clearTempDir();
            } catch (Throwable t) {
                ExceptionUtils.throwIfFatal(t);
            }

            try{
                AppConfig.deleteOldParseErrorFiles();
            } catch (Exception ignored) {
            }

            // Migrate downloads from old app-private path to user-visible location
            try {
                File oldBase = new File(getExternalFilesDir(null), "download");
                if (oldBase.exists() && oldBase.isDirectory()) {
                    UniFile newBase = DownloadSettings.getDownloadLocation();
                    if (newBase != null && "file".equals(newBase.getUri().getScheme())) {
                        File newBaseFile = new File(newBase.getUri().getPath());
                        File[] children = oldBase.listFiles();
                        if (children != null) {
                            for (File child : children) {
                                if (child.isDirectory()) {
                                    File dest = new File(newBaseFile, child.getName());
                                    if (!dest.exists()) {
                                        if (child.renameTo(dest)) {
                                            Log.i(TAG, "Migrated download dir: " + child.getName());
                                        }
                                    }
                                }
                            }
                        }
                        String[] remaining = oldBase.list();
                        if (remaining == null || remaining.length == 0) {
                            oldBase.delete();
                        }
                    }
                }
            } catch (Exception t) {
                Log.w(TAG, "Download directory migration failed", t);
            }
        });

        // Check app update
        update();

        // Update version code
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            Settings.putVersionCode(pi.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            // Ignore
        }

        if (DEBUG_PRINT_NATIVE_MEMORY || DEBUG_PRINT_IMAGE_COUNT) {
            debugPrint();
        }

        initialized = true;
    }

    private void clearTempDir() {
        File dir = AppConfig.getTempDir();
        if (null != dir) {
            FileUtils.deleteContent(dir);
        }
        dir = AppConfig.getExternalTempDir();
        if (null != dir) {
            FileUtils.deleteContent(dir);
        }
        // Add .nomedia to external temp dir
        CommonOperations.ensureNoMediaFile(UniFile.fromFile(AppConfig.getExternalTempDir()));
    }

    private void update() {
        int version = Settings.getVersionCode();
        if (version < 52) {
            Settings.putGuideGallery(true);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ServiceRegistry.INSTANCE.getClientModule().clearMemoryCache();
            ServiceRegistry.INSTANCE.getDataModule().clearGalleryDetailCache();
        }
    }

    private void debugPrint() {
        new Runnable() {
            @Override
            public void run() {
                if (DEBUG_PRINT_NATIVE_MEMORY) {
                    Log.i(TAG, "Native memory: " + FileUtils.humanReadableByteCount(
                            Debug.getNativeHeapAllocatedSize(), false));
                }
                SimpleHandler.getInstance().postDelayed(this, DEBUG_PRINT_INTERVAL);
            }
        }.run();
    }

    // ======== Activity registry ========

    public void registerActivity(Activity activity) {
        mActivityList.add(activity);
    }

    public void unregisterActivity(Activity activity) {
        mActivityList.remove(activity);
    }

    @Nullable
    public Activity getTopActivity() {
        if (!mActivityList.isEmpty()) {
            return mActivityList.get(mActivityList.size() - 1);
        } else {
            return null;
        }
    }

    // ======== Event pane ========

    public void showEventPane(String html){
        if (!Settings.getShowEhEvents()){
            return;
        }
        if (html==null){
            return;
        }
        Activity activity = getTopActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setMessage(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY))
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                dialog.setOnShowListener(d -> {
                    final View messageView = dialog.findViewById(android.R.id.message);
                    if (messageView instanceof TextView) {
                        ((TextView) messageView).setMovementMethod(LinkMovementMethod.getInstance());
                    }
                });
                try {
                    dialog.show();
                } catch (Exception t) {
                    // ignore
                }
            });
        }
    }

    // ======== Service override for device compatibility ========

    @Override
    public ComponentName startService(Intent service) {
        try {
            return super.startService(service);
        } catch (Throwable t) {
            ExceptionUtils.throwIfFatal(t);
            return null;
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        try {
            return super.bindService(service, conn, flags);
        } catch (Throwable t) {
            ExceptionUtils.throwIfFatal(t);
            return false;
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        try {
            super.unbindService(conn);
        } catch (Throwable t) {
            ExceptionUtils.throwIfFatal(t);
        }
    }

    // ======== Memory cache (instance method called by AdvancedFragment) ========

    public void clearMemoryCache() {
        ServiceRegistry.INSTANCE.getClientModule().clearMemoryCache();
        ServiceRegistry.INSTANCE.getDataModule().clearGalleryDetailCache();
    }

    // ======== Torrent download dedup (called by GalleryDetailScene) ========

    private final List<String> torrentList = new ArrayList<>();

    public static boolean addDownloadTorrent(@NonNull Context context, String url) {
        EhApplication application = ((EhApplication) context.getApplicationContext());
        if (application.torrentList.contains(url)) {
            return false;
        }
        application.torrentList.add(url);
        return true;
    }




    // --- GlobalStuff delegation ---

    public int putGlobalStuff(@NonNull Object o) {
        return ServiceRegistry.INSTANCE.getAppModule().putGlobalStuff(o);
    }

    public boolean containGlobalStuff(int id) {
        return ServiceRegistry.INSTANCE.getAppModule().containGlobalStuff(id);
    }

    public Object getGlobalStuff(int id) {
        return ServiceRegistry.INSTANCE.getAppModule().getGlobalStuff(id);
    }

    public Object removeGlobalStuff(int id) {
        return ServiceRegistry.INSTANCE.getAppModule().removeGlobalStuff(id);
    }

    public void removeGlobalStuff(Object o) {
        ServiceRegistry.INSTANCE.getAppModule().removeGlobalStuff(o);
    }

    // --- TempCache delegation ---

    public String putTempCache(@NonNull String key, @NonNull Object o) {
        return ServiceRegistry.INSTANCE.getAppModule().putTempCache(key, o);
    }

    public boolean containTempCache(@NonNull String key) {
        return ServiceRegistry.INSTANCE.getAppModule().containTempCache(key);
    }

    public Object getTempCache(@NonNull String key) {
        return ServiceRegistry.INSTANCE.getAppModule().getTempCache(key);
    }

    public Object removeTempCache(@NonNull String key) {
        return ServiceRegistry.INSTANCE.getAppModule().removeTempCache(key);
    }
}
