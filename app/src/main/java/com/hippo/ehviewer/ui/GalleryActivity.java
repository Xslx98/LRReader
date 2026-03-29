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

import static com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.ReadingSettings;
import com.hippo.ehviewer.settings.AppearanceSettings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.event.AppEventBus;
import com.hippo.ehviewer.event.GalleryActivityEvent;
import com.hippo.ehviewer.gallery.ArchiveGalleryProvider;
import com.hippo.ehviewer.gallery.DirGalleryProvider;
import com.hippo.ehviewer.gallery.EhGalleryProvider;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.gallery.LRRGalleryProvider;
import com.hippo.ehviewer.ui.gallery.GalleryImageOperations;
import com.hippo.ehviewer.ui.gallery.GalleryInputHandler;
import com.hippo.ehviewer.ui.gallery.GalleryMenuHelper;
import com.hippo.ehviewer.ui.gallery.GallerySliderController;
import com.hippo.ehviewer.widget.GalleryGuideView;
import com.hippo.ehviewer.widget.GalleryHeader;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.SimpleAdapter;
import com.hippo.lib.glview.view.GLRootView;
import com.hippo.unifile.UniFile;
import com.hippo.util.SystemUiHelper;
import com.hippo.widget.ColorView;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ResourcesUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;

import java.io.File;
import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class GalleryActivity extends EhActivity implements GalleryView.Listener,
        GalleryInputHandler.Callback, GalleryMenuHelper.SettingsCallback {

    public static final String ACTION_DIR = "dir";
    public static final String ACTION_EH = "eh";
    public static final String ACTION_LRR = "lrr";

    public static final String KEY_ACTION = "action";
    public static final String KEY_FILENAME = "filename";
    public static final String KEY_URI = "uri";
    public static final String KEY_GALLERY_INFO = "gallery_info";
    public static final String DATA_IN_EVENT = "data_in_event";
    public static final String KEY_PAGE = "page";
    public static final String KEY_CURRENT_INDEX = "current_index";

    private String mAction;
    private String mFilename;
    private Uri mUri;
    private GalleryInfo mGalleryInfo;
    private int mPage;

    @Nullable private GLRootView mGLRootView;
    @Nullable private GalleryView mGalleryView;
    @Nullable private GalleryProvider2 mGalleryProvider;
    @Nullable private GalleryAdapter mGalleryAdapter;

    @Nullable private SystemUiHelper mSystemUiHelper;

    @Nullable private ColorView mMaskView;
    @Nullable private View mClock;
    @Nullable private View mBattery;

    private boolean canFinish = false;

    // --- Extracted helpers ---
    private final GalleryInputHandler mInputHandler = new GalleryInputHandler(this);
    private final GallerySliderController mSliderController = new GallerySliderController();
    private final GalleryImageOperations mImageOps = new GalleryImageOperations(this);

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(3);

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case AppearanceSettings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Gallery;
            case AppearanceSettings.THEME_DARK:
                return R.style.AppTheme_Gallery_Dark;
            case AppearanceSettings.THEME_BLACK:
                return R.style.AppTheme_Gallery_Black;
        }
    }

    // ======== Provider factory ========

    private void buildProvider() {
        if (mGalleryProvider != null) {
            return;
        }

        if (ACTION_DIR.equals(mAction)) {
            if (mFilename != null) {
                if (mGalleryInfo != null) {
                    mGalleryProvider = new DirGalleryProvider(UniFile.fromFile(new File(mFilename)), this, mGalleryInfo);
                } else {
                    mGalleryProvider = new DirGalleryProvider(UniFile.fromFile(new File(mFilename)));
                }
            }
        } else if (ACTION_EH.equals(mAction)) {
            if (mGalleryInfo != null) {
                mGalleryProvider = new EhGalleryProvider(this, mGalleryInfo);
            }
        } else if (ACTION_LRR.equals(mAction)) {
            if (mGalleryInfo != null) {
                mGalleryProvider = new LRRGalleryProvider(this, mGalleryInfo);
            }
        } else if (Intent.ACTION_VIEW.equals(mAction)) {
            if (mUri != null) {
                mGalleryProvider = new ArchiveGalleryProvider(this, mUri);
            }
        }
    }

    // ======== Sticky event ========

    private void consumeStickyGalleryEvent() {
        if (mGalleryProvider != null) {
            return;
        }
        List<GalleryActivityEvent> cache = AppEventBus.INSTANCE.getGalleryActivityEvent().getReplayCache();
        if (!cache.isEmpty()) {
            GalleryActivityEvent event = cache.get(cache.size() - 1);
            mGalleryInfo = event.galleryInfo;
            mPage = event.pagePosition;
            buildProvider();
            onCreateView(null);
        }
    }

    // ======== Lifecycle ========

    private void onInit() {
        Intent intent = getIntent();
        if (intent == null) {
            canFinish = true;
            return;
        }

        mAction = intent.getAction();
        mFilename = intent.getStringExtra(KEY_FILENAME);
        mUri = intent.getData();
        mGalleryInfo = intent.getParcelableExtra(KEY_GALLERY_INFO);
        boolean onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false);
        if (!onEvent) {
            canFinish = true;
        }
        mPage = intent.getIntExtra(KEY_PAGE, -1);
        buildProvider();
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mAction = savedInstanceState.getString(KEY_ACTION);
        mFilename = savedInstanceState.getString(KEY_FILENAME);
        mUri = savedInstanceState.getParcelable(KEY_URI);
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO);
        mPage = savedInstanceState.getInt(KEY_PAGE, -1);
        mSliderController.setCurrentIndex(savedInstanceState.getInt(KEY_CURRENT_INDEX));
        buildProvider();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_ACTION, mAction);
        outState.putString(KEY_FILENAME, mFilename);
        outState.putParcelable(KEY_URI, mUri);
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo);
        }
        outState.putInt(KEY_PAGE, mPage);
        outState.putInt(KEY_CURRENT_INDEX, mSliderController.getCurrentIndex());
    }

    @Override
    @SuppressWarnings({"WrongConstant"})
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (ReadingSettings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
        super.onCreate(savedInstanceState);
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());
        builder.detectFileUriExposure();

        // Register "Save To" ActivityResultLauncher (must be done before onStart)
        mImageOps.setSaveToLauncher(
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                        mImageOps::handleSaveToResult));

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
        onCreateView(savedInstanceState);
        consumeStickyGalleryEvent();
    }

    @SuppressWarnings({"WrongConstant"})
    private void onCreateView(@Nullable Bundle savedInstanceState) {
        if (mGalleryProvider == null) {
            if (!canFinish) {
                return;
            }
            finish();
            return;
        }
        mGalleryProvider.start();

        // Get start page
        int startPage;
        if (savedInstanceState == null) {
            startPage = mPage >= 0 ? mPage : mGalleryProvider.getStartPage();
        } else {
            startPage = mSliderController.getCurrentIndex();
        }

        if (!isEglAvailable()) {
            mGalleryProvider.stop();
            showGlFallbackView();
            return;
        }

        setContentView(R.layout.activity_gallery);
        mGLRootView = (GLRootView) ViewUtils.$$(this, R.id.gl_root_view);
        mGalleryAdapter = new GalleryAdapter(mGLRootView, mGalleryProvider);
        Resources resources = getResources();
        mGalleryView = new GalleryView.Builder(this, mGalleryAdapter)
                .setListener(this)
                .setLayoutMode(ReadingSettings.getReadingDirection())
                .setScaleMode(ReadingSettings.getPageScaling())
                .setStartPosition(ReadingSettings.getStartPosition())
                .setStartPage(startPage)
                .setBackgroundColor(AttrResources.getAttrColor(this, android.R.attr.colorBackground))
                .setEdgeColor(AttrResources.getAttrColor(this, R.attr.colorEdgeEffect) & 0xffffff | 0x33000000)
                .setPagerInterval(ReadingSettings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0)
                .setScrollInterval(ReadingSettings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0)
                .setPageMinHeight(resources.getDimensionPixelOffset(R.dimen.gallery_page_min_height))
                .setPageInfoInterval(resources.getDimensionPixelOffset(R.dimen.gallery_page_info_interval))
                .setProgressColor(ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimary))
                .setProgressSize(resources.getDimensionPixelOffset(R.dimen.gallery_progress_size))
                .setPageTextColor(AttrResources.getAttrColor(this, android.R.attr.textColorSecondary))
                .setPageTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_page_text_size))
                .setPageTextTypeface(Typeface.DEFAULT)
                .setErrorTextColor(resources.getColor(R.color.red_500, null))
                .setErrorTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_error_text_size))
                .setDefaultErrorString(resources.getString(R.string.error_unknown))
                .setEmptyString(resources.getString(R.string.error_empty))
                .build();
        mGLRootView.setContentPane(mGalleryView);
        mGLRootView.setOnGenericMotionListener(mInputHandler::handleGenericMotion);
        mGalleryProvider.setGalleryView(mGalleryView);
        mGalleryProvider.setListener(mGalleryAdapter);
        mGalleryProvider.setGLRoot(mGLRootView);

        // Setup helpers
        mInputHandler.setGalleryView(mGalleryView);
        mImageOps.setGalleryProvider(mGalleryProvider);
        mImageOps.setGalleryInfo(mGalleryInfo);

        // System UI helper
        if (ReadingSettings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            mSystemUiHelper = new SystemUiHelper(this, SystemUiHelper.LEVEL_IMMERSIVE,
                    SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES | SystemUiHelper.FLAG_IMMERSIVE_STICKY);
            mSystemUiHelper.hide();
        }

        // Header views
        mMaskView = (ColorView) ViewUtils.$$(this, R.id.mask);
        mClock = ViewUtils.$$(this, R.id.clock);
        TextView progressView = (TextView) ViewUtils.$$(this, R.id.progress);
        mBattery = ViewUtils.$$(this, R.id.battery);
        mClock.setVisibility(ReadingSettings.getShowClock() ? View.VISIBLE : View.GONE);
        progressView.setVisibility(ReadingSettings.getShowProgress() ? View.VISIBLE : View.GONE);
        mBattery.setVisibility(ReadingSettings.getShowBattery() ? View.VISIBLE : View.GONE);

        // Slider controller
        View seekBarPanel = ViewUtils.$$(this, R.id.seek_bar_panel);
        ImageView autoTransferPanel = (ImageView) ViewUtils.$$(this, R.id.auto_transfer);
        TextView leftText = (TextView) ViewUtils.$$(seekBarPanel, R.id.left);
        TextView rightText = (TextView) ViewUtils.$$(seekBarPanel, R.id.right);
        ReversibleSeekBar seekBar = (ReversibleSeekBar) ViewUtils.$$(seekBarPanel, R.id.seek_bar);

        mSliderController.setViews(seekBarPanel, autoTransferPanel, leftText, rightText, seekBar, progressView);
        mSliderController.setSystemUiHelper(mSystemUiHelper);
        mSliderController.setGalleryView(mGalleryView);

        mInputHandler.setAutoTransferPanel(autoTransferPanel);
        autoTransferPanel.setOnClickListener(mInputHandler::toggleAutoRead);

        int size = mGalleryProvider.size();
        mSliderController.setSize(size);
        if (savedInstanceState == null) {
            mSliderController.setCurrentIndex(startPage);
        }
        if (mGalleryView != null) {
            mSliderController.setLayoutMode(mGalleryView.getLayoutMode());
            mInputHandler.setLayoutMode(mGalleryView.getLayoutMode());
        }

        // Keep screen on
        if (ReadingSettings.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Orientation
        setRequestedOrientation(resolveOrientation(ReadingSettings.getScreenRotation()));

        // Screen lightness
        setScreenLightness(ReadingSettings.getCustomScreenLightness(), ReadingSettings.getScreenLightness());

        // Cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            GalleryHeader galleryHeader = findViewById(R.id.gallery_header);
            galleryHeader.setOnApplyWindowInsetsListener((v, insets) -> {
                galleryHeader.setDisplayCutout(insets.getDisplayCutout());
                return insets;
            });
        }

        if (Settings.getGuideGallery()) {
            FrameLayout mainLayout = (FrameLayout) ViewUtils.$$(this, R.id.main);
            mainLayout.addView(new GalleryGuideView(this));
        }
    }

    private boolean isEglAvailable() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == null || display == EGL10.EGL_NO_DISPLAY) {
            return false;
        }
        int[] version = new int[2];
        if (!egl.eglInitialize(display, version)) {
            return false;
        }
        try {
            int[] numConfig = new int[1];
            return egl.eglChooseConfig(display, new int[]{EGL10.EGL_NONE}, null, 0, numConfig)
                    && numConfig[0] > 0;
        } catch (Exception e) {
            return false;
        } finally {
            egl.eglTerminate(display);
        }
    }

    private void showGlFallbackView() {
        setContentView(R.layout.activity_gallery_fallback);
        View close = ViewUtils.$$(this, R.id.gl_fallback_close);
        close.setOnClickListener(v -> finish());
        Log.w("GalleryActivity", "EGL init failed, switch to non-GL fallback page");
        Toast.makeText(this, R.string.gallery_gl_fallback_toast, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        mInputHandler.shutdown();
        mSliderController.destroy();

        mGLRootView = null;
        mGalleryView = null;
        if (mGalleryAdapter != null) {
            mGalleryAdapter.clearUploader();
            mGalleryAdapter = null;
        }
        if (mGalleryProvider != null) {
            mGalleryProvider.setListener(null);
            mGalleryProvider.stop();
            mGalleryProvider = null;
        }

        mMaskView = null;
        mClock = null;
        mBattery = null;

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        intent.putExtra("info", mGalleryInfo);
        setResult(LOCAL_GALLERY_INFO_CHANGE, intent);
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mGLRootView != null) {
            mGLRootView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mGLRootView != null) {
            mGLRootView.onResume();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        SimpleHandler.getInstance().postDelayed(() -> {
            if (hasFocus && mSystemUiHelper != null) {
                if (mSliderController.isShowSystemUi()) {
                    mSystemUiHelper.show();
                } else {
                    mSystemUiHelper.hide();
                }
            }
        }, 300);
    }

    // ======== Input delegation ========

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mInputHandler.handleKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mInputHandler.handleKeyUp(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ======== GalleryView.Listener (delegated to helpers) ========

    @Override
    public void onUpdateCurrentIndex(int index) {
        if (null != mGalleryProvider) {
            mGalleryProvider.putStartPage(index);
        }
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_CURRENT_INDEX, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapSliderArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_SLIDER_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapMenuArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_MENU_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapErrorText(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_ERROR_TEXT, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onLongPressPage(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_LONG_PRESS_PAGE, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onAutoTransferDone() {
        mInputHandler.onAutoTransferDone();
    }

    // ======== GalleryMenuHelper.SettingsCallback ========

    @Override
    public void onSettingsApplied(int screenRotation, int layoutMode, int scaleMode,
                                   int startPosition, boolean keepScreenOn,
                                   boolean showClock, boolean showProgress, boolean showBattery,
                                   boolean showPageInterval, boolean volumePage,
                                   boolean reverseVolumePage, boolean readingFullscreen,
                                   boolean customScreenLightness, int screenLightness,
                                   int transferTime) {
        if (mGalleryView == null) return;

        boolean oldReadingFullscreen = ReadingSettings.getReadingFullscreen();

        setRequestedOrientation(resolveOrientation(screenRotation));
        mGalleryView.setLayoutMode(layoutMode);
        mGalleryView.setScaleMode(scaleMode);
        mGalleryView.setStartPosition(startPosition);

        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mClock != null) mClock.setVisibility(showClock ? View.VISIBLE : View.GONE);
        if (mBattery != null) mBattery.setVisibility(showBattery ? View.VISIBLE : View.GONE);

        mGalleryView.setPagerInterval(showPageInterval ?
                getResources().getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0);
        mGalleryView.setScrollInterval(showPageInterval ?
                getResources().getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0);

        setScreenLightness(customScreenLightness, screenLightness);

        mSliderController.setLayoutMode(layoutMode);
        mInputHandler.setLayoutMode(layoutMode);

        if (oldReadingFullscreen != readingFullscreen) {
            recreate();
        }
    }

    // ======== Screen lightness ========

    private void setScreenLightness(boolean enable, int lightness) {
        if (null == mMaskView) {
            return;
        }
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (enable) {
            lightness = MathUtils.clamp(lightness, 0, 200);
            if (lightness > 100) {
                mMaskView.setColor(0);
                lp.screenBrightness = Math.max((lightness - 100) / 100.0f, 0.01f);
            } else {
                mMaskView.setColor(MathUtils.lerp(0xde, 0x00, lightness / 100.0f) << 24);
                lp.screenBrightness = 0.01f;
            }
        } else {
            mMaskView.setColor(0);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        w.setAttributes(lp);
    }

    // ======== Utility ========

    private static int resolveOrientation(int screenRotation) {
        switch (screenRotation) {
            default:
            case 0: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            case 1: return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case 2: return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case 3: return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
        }
    }

    // ======== NotifyTask (UI-thread dispatch) ========

    private class NotifyTask implements Runnable {

        public static final int KEY_LAYOUT_MODE = 0;
        public static final int KEY_SIZE = 1;
        public static final int KEY_CURRENT_INDEX = 2;
        public static final int KEY_TAP_SLIDER_AREA = 3;
        public static final int KEY_TAP_MENU_AREA = 4;
        public static final int KEY_TAP_ERROR_TEXT = 5;
        public static final int KEY_LONG_PRESS_PAGE = 6;

        private int mKey;
        private int mValue;

        public void setData(int key, int value) {
            mKey = key;
            mValue = value;
        }

        private void doTapMenuArea() {
            AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
            GalleryMenuHelper helper = new GalleryMenuHelper(builder.getContext(),
                    GalleryActivity.this,
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if (fromUser) {
                                setScreenLightness(true, progress);
                            }
                        }
                        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                    });
            AlertDialog dialog = builder.setTitle(R.string.gallery_menu_title)
                    .setView(helper.getView())
                    .setPositiveButton(android.R.string.ok, helper)
                    .show();
            mImageOps.applyImmersiveToDialog(dialog);
        }

        @Override
        public void run() {
            switch (mKey) {
                case KEY_LAYOUT_MODE:
                    mSliderController.setLayoutMode(mValue);
                    mInputHandler.setLayoutMode(mValue);
                    break;
                case KEY_SIZE:
                    mSliderController.setSize(mValue);
                    break;
                case KEY_CURRENT_INDEX:
                    mSliderController.setCurrentIndex(mValue);
                    break;
                case KEY_TAP_MENU_AREA:
                    doTapMenuArea();
                    break;
                case KEY_TAP_SLIDER_AREA:
                    mSliderController.onTapSliderArea();
                    break;
                case KEY_TAP_ERROR_TEXT:
                    if (mGalleryProvider != null) {
                        mGalleryProvider.forceRequest(mValue);
                    }
                    break;
                case KEY_LONG_PRESS_PAGE:
                    mImageOps.showPageDialog(mValue);
                    break;
            }
            mNotifyTaskPool.push(this);
        }
    }

    // ======== GalleryAdapter ========

    private class GalleryAdapter extends SimpleAdapter {

        public GalleryAdapter(@NonNull GLRootView glRootView, @NonNull GalleryProvider provider) {
            super(glRootView, provider);
        }

        @Override
        public void onDataChanged() {
            super.onDataChanged();

            if (mGalleryProvider != null) {
                int size = mGalleryProvider.size();
                NotifyTask task = mNotifyTaskPool.pop();
                if (task == null) {
                    task = new NotifyTask();
                }
                task.setData(NotifyTask.KEY_SIZE, size);
                SimpleHandler.getInstance().post(task);
            }
        }
    }
}
