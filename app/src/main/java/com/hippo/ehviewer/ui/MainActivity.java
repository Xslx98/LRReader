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

package com.hippo.ehviewer.ui;


import static com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.SecuritySettings;
import com.hippo.ehviewer.settings.NetworkSettings;
import com.hippo.ehviewer.settings.DownloadSettings;
import com.hippo.ehviewer.settings.AppearanceSettings;
import com.hippo.ehviewer.callBack.ImageChangeCallBack;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUrlOpener;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.ui.main.UserImageChange;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.ui.scene.AnalyticsScene;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.ehviewer.ui.scene.ServerConfigScene;
import com.hippo.ehviewer.ui.scene.ServerListScene;

import com.hippo.ehviewer.ui.scene.download.DownloadLabelsScene;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import com.hippo.ehviewer.ui.scene.gallery.list.FavoritesScene;
import com.hippo.ehviewer.ui.scene.LRRCategoriesScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene;
import com.hippo.ehviewer.ui.scene.gallery.list.SubscriptionsScene;

import com.hippo.ehviewer.ui.scene.history.HistoryScene;
import com.hippo.ehviewer.ui.scene.ProgressScene;
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene;
import com.hippo.ehviewer.ui.scene.SecurityScene;
import com.hippo.ehviewer.ui.scene.SolidScene;
import com.hippo.ehviewer.ui.splash.SplashActivity;
import com.hippo.ehviewer.updater.AppUpdater;
import com.hippo.ehviewer.widget.EhDrawerLayout;
import com.hippo.io.UniFileInputStreamPipe;
import com.hippo.network.Network;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.scene.StageActivity;
import com.hippo.unifile.UniFile;
import com.hippo.util.BitmapUtils;
import com.hippo.util.GifHandler;
import com.hippo.util.PermissionRequester;
import com.hippo.widget.AvatarImageView;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.ResourcesUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

public final class MainActivity extends StageActivity
        implements NavigationView.OnNavigationItemSelectedListener, ImageChangeCallBack, DrawerLayout.DrawerListener {

    private static final int PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE = 0;

    private static final int REQUEST_CODE_SETTINGS = 0;

    private static final String KEY_NAV_CHECKED_ITEM = "nav_checked_item";
//    private static final String KEY_CLIP_TEXT_HASH_CODE = "clip_text_hash_code";

    /*---------------
     Whole life cycle
     ---------------*/
    @Nullable
    private EhDrawerLayout mDrawerLayout;
    @Nullable
    private NavigationView mNavView;
    @Nullable
    private FrameLayout mRightDrawer;
    @Nullable
    private AvatarImageView mAvatar;
    @Nullable
    private ImageView mHeaderBackground;
    @Nullable
    private TextView mDisplayName;
    @Nullable
    UserImageChange userImageChange;

    private int mNavCheckedItem = 0;

    GifHandler gifHandler;

    Bitmap backgroundBit;

    Handler handlerB = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            int mNextFrame = gifHandler.updateFrame(backgroundBit);
            handlerB.sendEmptyMessageDelayed(1, mNextFrame);
            mHeaderBackground.setImageBitmap(backgroundBit);
        }
    };

    static {
        registerLaunchMode(SecurityScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);

        registerLaunchMode(AnalyticsScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(ServerConfigScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(ServerListScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(GalleryListScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TOP);
        registerLaunchMode(QuickSearchScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(SubscriptionsScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(GalleryDetailScene.class, SceneFragment.LAUNCH_MODE_STANDARD);

        registerLaunchMode(GalleryPreviewsScene.class, SceneFragment.LAUNCH_MODE_STANDARD);
        registerLaunchMode(DownloadsScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(DownloadLabelsScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(FavoritesScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(LRRCategoriesScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        registerLaunchMode(HistoryScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TOP);
        registerLaunchMode(ProgressScene.class, SceneFragment.LAUNCH_MODE_STANDARD);
    }

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case AppearanceSettings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Main;
            case AppearanceSettings.THEME_DARK:
                return R.style.AppTheme_Main_Dark;
            case AppearanceSettings.THEME_BLACK:
                return R.style.AppTheme_Main_Black;
        }
    }

    @Override
    public int getContainerViewId() {
        return R.id.fragment_container;
    }

    @NonNull
    @Override
    protected Announcer getLaunchAnnouncer() {
        if (SecuritySettings.hasPattern()) {
            return new Announcer(SecurityScene.class);
        } else if (!LRRAuthManager.isConfigured()) {
            // LANraragi: show server config if not yet configured
            return new Announcer(ServerConfigScene.class);
        } else {
            Bundle args = new Bundle();
            args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE);
            return new Announcer(GalleryListScene.class).setArgs(args);
        }
    }

    // LANraragi: simplified — only security gate and server config gate remain
    private Announcer processAnnouncer(Announcer announcer) {
        if (0 == getSceneCount()) {
            if (SecuritySettings.hasPattern()) {
                Bundle newArgs = new Bundle();
                newArgs.putString(SecurityScene.KEY_TARGET_SCENE, announcer.getClazz().getName());
                newArgs.putBundle(SecurityScene.KEY_TARGET_ARGS, announcer.getArgs());
                return new Announcer(SecurityScene.class).setArgs(newArgs);
            } else if (!LRRAuthManager.isConfigured()) {
                Bundle newArgs = new Bundle();
                newArgs.putString(SolidScene.KEY_TARGET_SCENE, announcer.getClazz().getName());
                newArgs.putBundle(SolidScene.KEY_TARGET_ARGS, announcer.getArgs());
                return new Announcer(ServerConfigScene.class).setArgs(newArgs);
            }
        }
        return announcer;
    }

    private File saveImageToTempFile(UniFile file) {
        if (null == file) {
            return null;
        }

        Bitmap bitmap = null;
        try {
            bitmap = BitmapUtils.decodeStream(new UniFileInputStreamPipe(file),
                    -1, -1, 500 * 500, false, false, null);
        } catch (OutOfMemoryError e) {
            // Ignore
        }
        if (null == bitmap) {
            return null;
        }

        File temp = AppConfig.createTempFile();
        if (null == temp) {
            return null;
        }

        OutputStream os = null;
        try {
            os = new FileOutputStream(temp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
            return temp;
        } catch (IOException e) {
            return null;
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }

        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri == null) {
                return false;
            }
            Announcer announcer = EhUrlOpener.parseUrl(uri.toString());
            if (announcer != null) {
                startScene(processAnnouncer(announcer));
                return true;
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if ("text/plain".equals(type)) {
                ListUrlBuilder builder = new ListUrlBuilder();
                builder.setKeyword(intent.getStringExtra(Intent.EXTRA_TEXT));
                startScene(processAnnouncer(GalleryListScene.getStartAnnouncer(builder)));
                return true;
            } else {
                assert type != null;
                if (type.startsWith("image/")) {
                    Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (null != uri) {
                        UniFile file = UniFile.fromUri(this, uri);
                        File temp = saveImageToTempFile(file);
                        if (null != temp) {
                            ListUrlBuilder builder = new ListUrlBuilder();
                            builder.setMode(ListUrlBuilder.MODE_IMAGE_SEARCH);
                            builder.setImagePath(temp.getPath());
                            builder.setUseSimilarityScan(true);
                            builder.setShowExpunged(true);
                            startScene(processAnnouncer(GalleryListScene.getStartAnnouncer(builder)));
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    @Override
    protected void onUnrecognizedIntent(@Nullable Intent intent) {
        Class<?> clazz = getTopSceneClass();
        if (clazz != null && SolidScene.class.isAssignableFrom(clazz)) {
            // KNOWN-ISSUE (P1): intent is silently dropped when a SolidScene (security/config gate) is showing
            return;
        }

        if (!handleIntent(intent)) {
            boolean handleUrl = false;
            if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
                handleUrl = true;
                Toast.makeText(this, R.string.error_cannot_parse_the_url, Toast.LENGTH_SHORT).show();
            }

            if (0 == getSceneCount()) {
                if (handleUrl) {
                    finish();
                } else {
                    Bundle args = new Bundle();
                    args.putString(GalleryListScene.KEY_ACTION, AppearanceSettings.getLaunchPageGalleryListSceneAction());
                    startScene(processAnnouncer(new Announcer(GalleryListScene.class).setArgs(args)));
                }
            }
        }
    }

    @Nullable
    @Override
    protected Announcer onStartSceneFromIntent(@NonNull Class<?> clazz, @Nullable Bundle args) {
        return processAnnouncer(new Announcer(clazz).setArgs(args));
    }

    @Override
    protected void onCreate2(@Nullable Bundle savedInstanceState) {
        Intent intent = getIntent();
        if (intent != null) {
            boolean res = intent.getBooleanExtra(SplashActivity.KEY_RESTART,false);
            if (res){
                savedInstanceState = null;
            }
        }
        setContentView(R.layout.activity_main);

        mDrawerLayout = (EhDrawerLayout) ViewUtils.$$(this, R.id.draw_view);
        mDrawerLayout.setDrawerListener(this);

        // Strip display cutout insets on left/right so fitsSystemWindows doesn't pad
        // for the notch/punch-hole area in landscape. Top/bottom are preserved for
        // status bar (portrait) and navigation bar.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            mDrawerLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    int left = Math.max(0, insets.getSystemWindowInsetLeft() - cutout.getSafeInsetLeft());
                    int top = insets.getSystemWindowInsetTop(); // keep top for status bar
                    int right = Math.max(0, insets.getSystemWindowInsetRight() - cutout.getSafeInsetRight());
                    int bottom = insets.getSystemWindowInsetBottom(); // keep bottom for nav bar
                    insets = insets.replaceSystemWindowInsets(left, top, right, bottom);
                }
                return v.onApplyWindowInsets(insets);
            });
        }
        mNavView = (NavigationView) ViewUtils.$$(this, R.id.nav_view);
        mRightDrawer = (FrameLayout) ViewUtils.$$(this, R.id.right_drawer);
        View headerLayout = mNavView.getHeaderView(0);
        mAvatar = (AvatarImageView) ViewUtils.$$(headerLayout, R.id.avatar);
        mAvatar.setOnClickListener(l -> onAvatarChange());
        mHeaderBackground = (ImageView) ViewUtils.$$(headerLayout, R.id.header_background);
        mHeaderBackground.setOnClickListener(l -> onBackgroundChange());
        initUserImage();
        updateProfile();
        mDisplayName = (TextView) ViewUtils.$$(headerLayout, R.id.display_name);
        TextView mChangeTheme = (TextView) ViewUtils.$$(this, R.id.change_theme);



        mDrawerLayout.setStatusBarColor(ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimaryDark));
//        mDrawerLayout.setStatusBarColor(0);

        if (mNavView != null) {
//            if (Settings.isLogin()){
//                MenuItem newsItem = mNavView.getMenu().findItem(R.id.nav_eh_news);
//                newsItem.setVisible(true);
//            }
            mNavView.setNavigationItemSelectedListener(this);
        }
        if (AppearanceSettings.getTheme() == 0) {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_light));

            mChangeTheme.setBackgroundColor(getColor(R.color.white));
        } else if (AppearanceSettings.getTheme() == 1) {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_other));
            mChangeTheme.setBackgroundColor(getColor(R.color.grey_850));
        } else {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_other));
            mChangeTheme.setBackgroundColor(getColor(R.color.black));
        }

        mChangeTheme.setText(getThemeText());
        mChangeTheme.setOnClickListener(v -> {
            AppearanceSettings.putTheme(getNextTheme());
            ((EhApplication) getApplication()).recreate();
        });

        if (savedInstanceState == null) {
            onInit();
            checkDownloadLocation();
            if (NetworkSettings.getCellularNetworkWarning()) {
                checkCellularNetwork();
            }
        } else {
            onRestore(savedInstanceState);
        }
        EhTagDatabase.update(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // LANraragi: EhViewer auto-update check disabled
    }

    private void initUserImage() {
        File headerBackgroundFile = Settings.getUserImageFile(Settings.USER_BACKGROUND_IMAGE);
        initBackgroundImageData(headerBackgroundFile);
    }

    private void initBackgroundImageData(File file) {
        if (file != null) {
            String name = file.getName();
            String[] ns = name.split("\\.");
            if (ns[1].equals("gif") || ns[1].equals("GIF")) {
                gifHandler = new GifHandler(file.getAbsolutePath());
                int width = gifHandler.getWidth();
                int height = gifHandler.getHeight();
                backgroundBit = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                int nextFrame = gifHandler.updateFrame(backgroundBit);
                handlerB.sendEmptyMessageDelayed(1, nextFrame);
            } else {
                backgroundBit = BitmapFactory.decodeFile(file.getPath());
                assert mHeaderBackground != null;
                mHeaderBackground.setImageBitmap(backgroundBit);
            }
        }
    }

    @Override
    public void backgroundSourceChange(File file) {
        if (file == null) {
            // Reset to default background
            if (mHeaderBackground != null) {
                mHeaderBackground.setImageResource(R.drawable.sadpanda_low_poly);
            }
            backgroundBit = null;
        } else {
            initBackgroundImageData(file);
        }
    }

    /**
     * Reload avatar from settings (or reset to default).
     * Called by UserImageChange.resetToDefault().
     */
    public void loadAvatar() {
        if (mAvatar == null) return;
        File userAvatarFile = Settings.getUserImageFile(Settings.USER_AVATAR_IMAGE);
        if (userAvatarFile != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(userAvatarFile.getPath());
            if (bitmap != null) {
                mAvatar.load(new android.graphics.drawable.BitmapDrawable(mAvatar.getResources(), bitmap));
            } else {
                mAvatar.load(R.drawable.default_avatar);
            }
        } else {
            mAvatar.load(R.drawable.default_avatar);
        }
    }

    private String getThemeText() {
        int resId;
        switch (AppearanceSettings.getTheme()) {
            default:
            case AppearanceSettings.THEME_LIGHT:
                resId = R.string.theme_light;
                break;
            case AppearanceSettings.THEME_DARK:
                resId = R.string.theme_dark;
                break;
            case AppearanceSettings.THEME_BLACK:
                resId = R.string.theme_black;
                break;
        }
        return getString(resId);
    }

    private int getNextTheme() {
        switch (AppearanceSettings.getTheme()) {
            default:
            case AppearanceSettings.THEME_LIGHT:
                return AppearanceSettings.THEME_DARK;
            case AppearanceSettings.THEME_DARK:
                return AppearanceSettings.THEME_BLACK;
            case AppearanceSettings.THEME_BLACK:
                return AppearanceSettings.THEME_LIGHT;
        }
    }

    private void checkDownloadLocation() {
        UniFile uniFile = DownloadSettings.getDownloadLocation();
        // null == uniFile for first start
        if (null == uniFile || uniFile.ensureDir()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.waring)
                .setMessage(R.string.invalid_download_location)
                .setPositiveButton(R.string.get_it, null)
                .show();
    }

    private void checkCellularNetwork() {
        if (Network.getActiveNetworkType(this) == ConnectivityManager.TYPE_MOBILE) {
            showTip(R.string.cellular_network_warning, BaseScene.LENGTH_SHORT);
        }
    }

    private void onInit() {
        // EH cookie auth check removed — login state is managed via LRRAuthManager
    }


    private void onRestore(Bundle savedInstanceState) {
        mNavCheckedItem = savedInstanceState.getInt(KEY_NAV_CHECKED_ITEM);
    }

    @Override
    public void onSaveInstanceState(Bundle outState, @NonNull PersistableBundle outPersistentState) {
//        super.onSaveInstanceState(outState, outPersistentState);
        outState.putInt(KEY_NAV_CHECKED_ITEM, mNavCheckedItem);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mDrawerLayout = null;
        mNavView = null;
        mRightDrawer = null;
        mAvatar = null;
        mDisplayName = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        setNavCheckedItem(mNavCheckedItem);

        checkClipboardUrl();
    }

    @Override
    protected void onTransactScene() {
        super.onTransactScene();

        checkClipboardUrl();
    }

    private void checkClipboardUrl() {
        SimpleHandler.getInstance().postDelayed(() -> {
            if (!isSolid()) {
                checkClipboardUrlInternal();
            }
        }, 300);
    }

    private boolean isSolid() {
        Class<?> topClass = getTopSceneClass();
        return topClass == null || SolidScene.class.isAssignableFrom(topClass);
    }

    private String getTextFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            if (clipboard != null) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                    return clip.getItemAt(0).getText().toString();
                }
            }
        } catch (RuntimeException ignore) {
        }
        return null;
    }



    private void checkClipboardUrlInternal() {
        // LANraragi: clipboard URL monitoring disabled (was E-Hentai specific)
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.length == 1 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.you_rejected_me, Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onSceneViewCreated(SceneFragment scene, Bundle savedInstanceState) {
        super.onSceneViewCreated(scene, savedInstanceState);

        if (scene instanceof BaseScene && mRightDrawer != null && mDrawerLayout != null) {
            BaseScene baseScene = (BaseScene) scene;
            mRightDrawer.removeAllViews();
            View drawerView = baseScene.createDrawerView(
                    baseScene.getLayoutInflater2(), mRightDrawer, savedInstanceState);
            if (drawerView != null) {
                mRightDrawer.addView(drawerView);
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            } else {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            }
        }
    }

    @Override
    public void onSceneViewDestroyed(SceneFragment scene) {
        super.onSceneViewDestroyed(scene);

        if (scene instanceof BaseScene) {
            BaseScene baseScene = (BaseScene) scene;
            baseScene.destroyDrawerView();
        }
    }

    public void updateProfile() {
        if (null != mAvatar) {
            String avatarUrl = Settings.getAvatar();
            if (TextUtils.isEmpty(avatarUrl)) {
                File userAvatarFile = Settings.getUserImageFile(Settings.USER_AVATAR_IMAGE);
                if (userAvatarFile != null) {
                    Bitmap bitmap = BitmapFactory.decodeFile(userAvatarFile.getPath());
                    Drawable drawable = new BitmapDrawable(mAvatar.getResources(), bitmap);
                    mAvatar.load(drawable);
                } else {
                    mAvatar.load(R.drawable.default_avatar);
                }
            } else {
                mAvatar.load(avatarUrl, avatarUrl);
            }
        }

        if (null != mDisplayName) {
            String displayName = Settings.getDisplayName();
            if (TextUtils.isEmpty(displayName)) {
                displayName = getString(R.string.default_display_name);
            }
            Toast.makeText(this, displayName, Toast.LENGTH_LONG).show();
            mDisplayName.setText(displayName);
        }

    }

    public void addAboveSnackView(View view) {
        if (mDrawerLayout != null) {
            mDrawerLayout.addAboveSnackView(view);
        }
    }

    public void removeAboveSnackView(View view) {
        if (mDrawerLayout != null) {
            mDrawerLayout.removeAboveSnackView(view);
        }
    }

    /**
     * 更换壁纸
     */
    public void onBackgroundChange() {
        if (userImageChange != null) {
            userImageChange = null;
        }
        userImageChange = new UserImageChange(MainActivity.this,
                UserImageChange.CHANGE_BACKGROUND,
                getLayoutInflater(),
                LayoutInflater.from(MainActivity.this),
                this
        );
        userImageChange.showImageChangeDialog();
    }

    /**
     * 更换头像
     */
    public void onAvatarChange() {
        if (userImageChange != null) {
            userImageChange = null;
        }
        userImageChange = new UserImageChange(MainActivity.this,
                UserImageChange.CHANGE_AVATAR,
                getLayoutInflater(),
                LayoutInflater.from(MainActivity.this),
                this
        );

        userImageChange.showImageChangeDialog();
    }

    public void setDrawerLockMode(int lockMode, int edgeGravity) {
        if (mDrawerLayout != null) {
            mDrawerLayout.setDrawerLockMode(lockMode, edgeGravity);
        }
    }

    public void openDrawer(int drawerGravity) {
        if (mDrawerLayout != null) {
            mDrawerLayout.openDrawer(drawerGravity);
        }
    }

    public void closeDrawer(int drawerGravity) {
        if (mDrawerLayout != null) {
            mDrawerLayout.closeDrawer(drawerGravity);
        }
    }

    public void toggleDrawer(int drawerGravity) {
        if (mDrawerLayout != null) {
            if (mDrawerLayout.isDrawerOpen(drawerGravity)) {
                mDrawerLayout.closeDrawer(drawerGravity);
            } else {
                mDrawerLayout.openDrawer(drawerGravity);
            }
        }
    }

    public void setDrawerGestureBlocker(DrawerLayout.GestureBlocker gestureBlocker) {
        if (mDrawerLayout != null) {
            mDrawerLayout.setGestureBlocker(gestureBlocker);
        }
    }

    public boolean isDrawersVisible() {
        if (mDrawerLayout != null) {
            return mDrawerLayout.isDrawersVisible();
        } else {
            return false;
        }
    }

    public void setNavCheckedItem(@IdRes int resId) {
        mNavCheckedItem = resId;
        if (mNavView != null) {
            if (resId == 0) {
                mNavView.setCheckedItem(R.id.nav_stub);
            } else {
                mNavView.setCheckedItem(resId);
            }
        }
    }

    public void showTip(@StringRes int id, int length) {
        showTip(getString(id), length);
    }

    /**
     * If activity is running, show snack bar, otherwise show toast
     */
    public void showTip(CharSequence message, int length) {
        if (null != mDrawerLayout) {
            Snackbar.make(mDrawerLayout, message,
                    length == BaseScene.LENGTH_LONG ? 5000 : 3000).show();
        } else {
            Toast.makeText(this, message,
                    length == BaseScene.LENGTH_LONG ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && (mDrawerLayout.isDrawerOpen(Gravity.LEFT) ||
                mDrawerLayout.isDrawerOpen(Gravity.RIGHT))) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressLint({"NonConstantResourceId", "RtlHardcoded"})
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Don't select twice
        if (item.isChecked()) {
            return false;
        }

        int id = item.getItemId();

        switch (item.getItemId()) {
            case R.id.nav_homepage:
                Bundle nav_homepage = new Bundle();
                nav_homepage.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE);
                startSceneFirstly(new Announcer(GalleryListScene.class)
                        .setArgs(nav_homepage));
                break;
            case R.id.nav_favourite:
                startScene(new Announcer(LRRCategoriesScene.class));
                break;
            case R.id.nav_history:
                startScene(new Announcer(HistoryScene.class));
                break;
            case R.id.nav_downloads:
                startScene(new Announcer(DownloadsScene.class));
                break;
            case R.id.nav_settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivityForResult(intent, REQUEST_CODE_SETTINGS);
                break;
            case R.id.nav_server_config:
                // Show server list if profiles exist, otherwise direct to config
                int profileCount = EhDB.getAllServerProfiles().size();
                if (profileCount > 0) {
                    startScene(new Announcer(ServerListScene.class));
                } else {
                    startScene(new Announcer(ServerConfigScene.class));
                }
                break;
            default:
                break;
        }

        if (id != R.id.nav_stub && mDrawerLayout != null) {
            mDrawerLayout.closeDrawers();
        }


        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SETTINGS) {
            if (RESULT_OK == resultCode) {
                refreshTopScene();
            }
            return;
        }
        if ((requestCode == UserImageChange.TAKE_CAMERA || requestCode == UserImageChange.PICK_PHOTO || requestCode == UserImageChange.CROP_PHOTO) && userImageChange != null) {
            userImageChange.saveImageForResult(requestCode, resultCode, data, mAvatar);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onDrawerSlide(View drawerView, float percent) {

    }

    @Override
    public void onDrawerOpened(View drawerView) {
    }

    @Override
    public void onDrawerClosed(View drawerView) {
    }

    @Override
    public void onDrawerStateChanged(View drawerView, int newState) {

    }
}
