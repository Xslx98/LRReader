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

package com.hippo.ehviewer.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.AppLockGate
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.event.GalleryActivityEvent
import com.hippo.ehviewer.gallery.ArchiveGalleryProvider
import com.lanraragi.reader.domain.Archive
import com.hippo.ehviewer.gallery.DirGalleryProvider
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.gallery.LRRGalleryProvider
import com.hippo.ehviewer.ui.gallery.GalleryImageOperations
import com.hippo.ehviewer.ui.gallery.GalleryInputHandler
import com.hippo.ehviewer.ui.gallery.GalleryMenuHelper
import com.hippo.ehviewer.ui.gallery.GallerySliderController
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.widget.GalleryGuideView
import com.hippo.ehviewer.widget.GalleryHeader
import com.hippo.ehviewer.widget.ReversibleSeekBar
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.glgallery.GalleryView
import com.hippo.lib.glgallery.SimpleAdapter
import com.hippo.lib.glview.view.GLRootView
import com.hippo.unifile.UniFile
import com.hippo.util.SystemUiHelper
import com.hippo.widget.ColorView
import com.hippo.lib.yorozuya.ConcurrentPool
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.lib.yorozuya.ResourcesUtils
import com.hippo.lib.yorozuya.ViewUtils
import java.io.File
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

class GalleryActivity : EhActivity(), GalleryView.Listener,
    GalleryInputHandler.Callback, GalleryMenuHelper.SettingsCallback {

    companion object {
        const val ACTION_DIR = "dir"
        const val ACTION_LRR = "lrr"

        const val KEY_ACTION = "action"
        const val KEY_FILENAME = "filename"
        const val KEY_URI = "uri"
        const val KEY_ARCHIVE = "archive"

        /** onBackPressed result-extra carrying the (possibly mutated) archive back to the launching scene. */
        const val EXTRA_RESULT_ARCHIVE = "result_archive"
        const val DATA_IN_EVENT = "data_in_event"
        const val KEY_PAGE = "page"
        const val KEY_CURRENT_INDEX = "current_index"

        // NotifyTask keys
        private const val NOTIFY_KEY_LAYOUT_MODE = 0
        private const val NOTIFY_KEY_SIZE = 1
        private const val NOTIFY_KEY_CURRENT_INDEX = 2
        private const val NOTIFY_KEY_TAP_SLIDER_AREA = 3
        private const val NOTIFY_KEY_TAP_MENU_AREA = 4
        private const val NOTIFY_KEY_TAP_ERROR_TEXT = 5
        private const val NOTIFY_KEY_LONG_PRESS_PAGE = 6

        @JvmStatic
        private fun resolveOrientation(screenRotation: Int): Int = when (screenRotation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mAction: String? = null
    private var mFilename: String? = null
    private var mUri: android.net.Uri? = null
    private var mArchive: Archive? = null
    private var mPage = 0

    private var mGLRootView: GLRootView? = null
    private var mGalleryView: GalleryView? = null
    private var mGalleryProvider: GalleryProvider2? = null
    private var mGalleryAdapter: GalleryAdapter? = null

    private var mSystemUiHelper: SystemUiHelper? = null

    private var mMaskView: ColorView? = null
    private var mClock: View? = null
    private var mProgress: View? = null
    private var mBattery: View? = null

    private var canFinish = false

    // --- Extracted helpers ---
    private val mInputHandler = GalleryInputHandler(this)
    private val mSliderController = GallerySliderController()
    private val mImageOps = GalleryImageOperations(this)

    private val mNotifyTaskPool = ConcurrentPool<NotifyTask>(3)

    override fun getThemeResId(theme: Int): Int = when (theme) {
        AppearanceSettings.THEME_DARK -> R.style.AppTheme_Gallery_Dark
        AppearanceSettings.THEME_BLACK -> R.style.AppTheme_Gallery_Black
        else -> R.style.AppTheme_Gallery
    }

    // ======== Provider factory ========

    private fun buildProvider() {
        if (mGalleryProvider != null) {
            return
        }

        when (mAction) {
            ACTION_DIR -> {
                val filename = mFilename
                if (filename != null) {
                    val uniFile = UniFile.fromFile(File(filename)) ?: return
                    val archive = mArchive
                    mGalleryProvider = if (archive != null) {
                        DirGalleryProvider(uniFile, this, archive.arcid)
                    } else {
                        DirGalleryProvider(uniFile)
                    }
                }
            }
            ACTION_LRR -> {
                val archive = mArchive
                if (archive != null) {
                    mGalleryProvider = LRRGalleryProvider(this, archive.arcid)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = mUri
                if (uri != null) {
                    mGalleryProvider = ArchiveGalleryProvider(this, uri)
                }
            }
        }
    }

    // ======== Sticky event ========

    private fun consumeStickyGalleryEvent() {
        if (mGalleryProvider != null) {
            return
        }
        val cache = AppEventBus.galleryActivityEvent.replayCache
        if (cache.isNotEmpty()) {
            val event = cache[cache.size - 1]
            mArchive = event.archive
            mPage = event.pagePosition
            buildProvider()
            onCreateView(null)
        }
    }

    // ======== Lifecycle ========

    private fun onInit() {
        val intent = intent ?: run {
            canFinish = true
            return
        }

        mAction = intent.action
        mFilename = intent.getStringExtra(KEY_FILENAME)
        mUri = intent.data
        mArchive = intent.getParcelableExtra(KEY_ARCHIVE)
        val onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false)
        if (!onEvent) {
            canFinish = true
        }
        mPage = intent.getIntExtra(KEY_PAGE, -1)
        buildProvider()
        // Reader entry counts as reading: ensure history-subsystem
        // membership for the archive so per-archive state (intra-page
        // scroll fraction, etc.) lands on a row that the history
        // queries can see. The reader can be launched directly from
        // the downloads list (DownloadGalleryOpenHelper) which
        // bypasses the detail page and therefore never calls
        // HistoryRepository.putHistoryInfo. Fire-and-forget on the
        // app-wide IO scope.
        mArchive?.let { archive ->
            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    ServiceRegistry.dataModule.historyRepository.putHistoryInfo(archive)
                } catch (e: Exception) {
                    Log.w("GalleryActivity", "Failed to record history: ${e.message}")
                }
            }
        }
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mAction = savedInstanceState.getString(KEY_ACTION)
        mFilename = savedInstanceState.getString(KEY_FILENAME)
        mUri = savedInstanceState.getParcelable(KEY_URI)
        mArchive = savedInstanceState.getParcelable(KEY_ARCHIVE)
        mPage = savedInstanceState.getInt(KEY_PAGE, -1)
        mSliderController.currentIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX)
        buildProvider()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTION, mAction)
        outState.putString(KEY_FILENAME, mFilename)
        outState.putParcelable(KEY_URI, mUri)
        mArchive?.let { outState.putParcelable(KEY_ARCHIVE, it) }
        outState.putInt(KEY_PAGE, mPage)
        outState.putInt(KEY_CURRENT_INDEX, mSliderController.currentIndex)
    }

    @Suppress("WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (ReadingSettings.getReadingFullscreen()) {
            val w = window
            w.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            w.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            )
        }
        super.onCreate(savedInstanceState)
        val builder = StrictMode.VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
        builder.detectFileUriExposure()

        // Register "Save To" ActivityResultLauncher (must be done before onStart)
        mImageOps.saveToLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            mImageOps::handleSaveToResult
        )

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
        onCreateView(savedInstanceState)
        consumeStickyGalleryEvent()
    }

    @Suppress("WrongConstant")
    private fun onCreateView(savedInstanceState: Bundle?) {
        val galleryProvider = mGalleryProvider ?: run {
            if (canFinish) finish()
            return
        }
        galleryProvider.start()

        // Get start page
        val startPage: Int = if (savedInstanceState == null) {
            if (mPage >= 0) mPage else galleryProvider.getStartPage()
        } else {
            mSliderController.currentIndex
        }

        if (!isEglAvailable()) {
            galleryProvider.stop()
            showGlFallbackView()
            return
        }

        setContentView(R.layout.activity_gallery)
        val glRootView = ViewUtils.`$$`(this, R.id.gl_root_view) as GLRootView
        mGLRootView = glRootView
        val galleryAdapter = GalleryAdapter(glRootView, galleryProvider)
        mGalleryAdapter = galleryAdapter
        val resources = resources
        val galleryView = GalleryView.Builder(this, galleryAdapter)
            .setListener(this)
            .setLayoutMode(ReadingSettings.getReadingDirection())
            .setScaleMode(ReadingSettings.getPageScaling())
            .setStartPosition(ReadingSettings.getStartPosition())
            .setStartPage(startPage)
            .setBackgroundColor(AttrResources.getAttrColor(this, android.R.attr.colorBackground))
            .setEdgeColor(
                AttrResources.getAttrColor(this, R.attr.colorEdgeEffect) and 0xffffff or 0x33000000
            )
            .setPagerInterval(
                if (ReadingSettings.getShowPageInterval())
                    resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) else 0
            )
            // Vertical (LAYOUT_TOP_TO_BOTTOM) mode is the project's
            // long-strip / webtoon view: the standard convention
            // across Mihon / Tachiyomi / Yokai is *no* gap between
            // pages so the strip reads continuously. The user-visible
            // "show page interval" toggle still governs pager-mode
            // spacing (where the gap is a useful visual separator
            // between discrete pages).
            .setScrollInterval(0)
            .setPageMinHeight(resources.getDimensionPixelOffset(R.dimen.gallery_page_min_height))
            .setPageInfoInterval(resources.getDimensionPixelOffset(R.dimen.gallery_page_info_interval))
            .setProgressColor(
                ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimary)
            )
            .setProgressSize(resources.getDimensionPixelOffset(R.dimen.gallery_progress_size))
            .setPageTextColor(
                AttrResources.getAttrColor(this, android.R.attr.textColorSecondary)
            )
            .setPageTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_page_text_size))
            .setPageTextTypeface(Typeface.DEFAULT)
            .setErrorTextColor(resources.getColor(R.color.red_500, null))
            .setErrorTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_error_text_size))
            .setDefaultErrorString(resources.getString(R.string.error_unknown))
            .setEmptyString(resources.getString(R.string.error_empty))
            .build()
        mGalleryView = galleryView
        glRootView.setContentPane(galleryView)
        glRootView.setOnGenericMotionListener(mInputHandler::handleGenericMotion)
        galleryProvider.galleryView = galleryView
        galleryProvider.setListener(galleryAdapter)
        galleryProvider.setGLRoot(glRootView)

        // Setup helpers
        mInputHandler.galleryView = galleryView
        mImageOps.galleryProvider = galleryProvider
        mImageOps.archive = mArchive

        // System UI helper
        if (ReadingSettings.getReadingFullscreen()) {
            val w = window
            w.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            w.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            )
            val systemUiHelper = SystemUiHelper(
                this, SystemUiHelper.LEVEL_IMMERSIVE,
                SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES or SystemUiHelper.FLAG_IMMERSIVE_STICKY
            )
            mSystemUiHelper = systemUiHelper
            systemUiHelper.hide()
        }

        // Header views
        mMaskView = ViewUtils.`$$`(this, R.id.mask) as ColorView
        val clock = ViewUtils.`$$`(this, R.id.clock)
        mClock = clock
        val progress = ViewUtils.`$$`(this, R.id.progress) as TextView
        mProgress = progress
        val battery = ViewUtils.`$$`(this, R.id.battery)
        mBattery = battery
        clock.visibility = if (ReadingSettings.getShowClock()) View.VISIBLE else View.GONE
        progress.visibility = if (ReadingSettings.getShowProgress()) View.VISIBLE else View.GONE
        battery.visibility = if (ReadingSettings.getShowBattery()) View.VISIBLE else View.GONE

        // Slider controller
        val seekBarPanel = ViewUtils.`$$`(this, R.id.seek_bar_panel)
        val autoTransferPanel = ViewUtils.`$$`(this, R.id.auto_transfer) as ImageView
        val leftText = ViewUtils.`$$`(seekBarPanel, R.id.left) as TextView
        val rightText = ViewUtils.`$$`(seekBarPanel, R.id.right) as TextView
        val seekBar = ViewUtils.`$$`(seekBarPanel, R.id.seek_bar) as ReversibleSeekBar

        mSliderController.setViews(
            seekBarPanel, autoTransferPanel, leftText, rightText, seekBar, progress
        )
        mSliderController.setSystemUiHelper(mSystemUiHelper)
        mSliderController.setGalleryView(galleryView)

        mInputHandler.autoTransferPanel = autoTransferPanel
        autoTransferPanel.setOnClickListener { v -> mInputHandler.toggleAutoRead(v) }

        val size = galleryProvider.size()
        mSliderController.size = size
        if (savedInstanceState == null) {
            mSliderController.currentIndex = startPage
        }
        mSliderController.layoutMode = galleryView.layoutMode
        mInputHandler.layoutMode = galleryView.layoutMode

        // Keep screen on
        if (ReadingSettings.getKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Orientation
        requestedOrientation = resolveOrientation(ReadingSettings.getScreenRotation())

        // Screen lightness
        setScreenLightness(
            ReadingSettings.getCustomScreenLightness(),
            ReadingSettings.getScreenLightness()
        )

        // Cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

            val galleryHeader = findViewById<GalleryHeader>(R.id.gallery_header)
            galleryHeader.setOnApplyWindowInsetsListener { _, insets ->
                galleryHeader.setDisplayCutout(insets.displayCutout)
                insets
            }
        }

        if (GuideSettings.getGuideGallery()) {
            val mainLayout = ViewUtils.`$$`(this, R.id.main) as FrameLayout
            mainLayout.addView(GalleryGuideView(this))
        }
    }

    private fun isEglAvailable(): Boolean {
        val egl = EGLContext.getEGL() as EGL10
        val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            ?: return false
        if (display == EGL10.EGL_NO_DISPLAY) {
            return false
        }
        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) {
            return false
        }
        return try {
            val numConfig = IntArray(1)
            egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_NONE), null, 0, numConfig)
                && numConfig[0] > 0
        } catch (e: Exception) {
            false
        } finally {
            egl.eglTerminate(display)
        }
    }

    private fun showGlFallbackView() {
        setContentView(R.layout.activity_gallery_fallback)
        val close = ViewUtils.`$$`(this, R.id.gl_fallback_close)
        close.setOnClickListener { finish() }
        Log.w("GalleryActivity", "EGL init failed, switch to non-GL fallback page")
        Toast.makeText(this, R.string.gallery_gl_fallback_toast, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        mInputHandler.shutdown()
        mSliderController.destroy()
        // Drop pending posts (queued NotifyTasks + the 300ms onWindowFocusChanged
        // runnable) so none run against a destroyed Activity/window.
        mainHandler.removeCallbacksAndMessages(null)

        mGLRootView = null
        mGalleryView = null
        mGalleryAdapter?.clearUploader()
        mGalleryAdapter = null
        mGalleryProvider?.let { provider ->
            provider.setListener(null)
            provider.stop()
        }
        mGalleryProvider = null

        mSystemUiHelper = null
        mMaskView = null
        mClock = null
        mProgress = null
        mBattery = null

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent()
        intent.putExtra(EXTRA_RESULT_ARCHIVE, mArchive)
        setResult(DownloadsScene.LOCAL_GALLERY_INFO_CHANGE, intent)
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        val gv = mGalleryView
        val provider = mGalleryProvider
        if (gv != null && provider != null && gv.layoutMode == GalleryView.LAYOUT_TOP_TO_BOTTOM) {
            provider.putScrollFraction(gv.currentScrollFraction)
        }
        // Suspend auto page-turn while backgrounded; resumed in onResume().
        mInputHandler.pauseAutoRead()
        mGLRootView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        // EhActivity.onForegroundLockCheck may have launched MainActivity for
        // the security re-prompt — when CLEAR_TOP pops us, we end up
        // finishing. Don't touch GL resources on a doomed activity.
        if (isFinishing) return
        mGLRootView?.onResume()
        mInputHandler.resumeAutoRead()
    }

    /**
     * Before the EhActivity default bounces us back to MainActivity for the
     * security prompt, stash a self-resume intent so SecurityScene can
     * re-launch the reader on the same page after a successful unlock.
     * The stash is process-scoped — process death drops it, which is fine
     * since process death = cold start = lock comes from `getLaunchAnnouncer`.
     */
    override fun onForegroundLockCheck() {
        if (AppLockGate.shouldRelock && SecuritySettings.hasPattern()) {
            buildResumeIntent()?.let { AppLockGate.stashResumeIntent(it) }
        }
        super.onForegroundLockCheck()
    }

    private fun buildResumeIntent(): Intent? {
        val original = intent ?: return null
        val currentPage = mSliderController.currentIndex.takeIf { it >= 0 } ?: mPage
        return Intent(this, GalleryActivity::class.java).apply {
            action = original.action
            // Preserve action-specific extras (filename / uri / archive),
            // then override KEY_PAGE with the page the user was actually on.
            original.extras?.let { putExtras(it) }
            original.data?.let { data = it }
            putExtra(KEY_PAGE, currentPage)
            // Don't carry over DATA_IN_EVENT — sticky-event consumption
            // already happened on the original launch.
            removeExtra(DATA_IN_EVENT)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        mainHandler.postDelayed({
            if (hasFocus) {
                val uiHelper = mSystemUiHelper ?: return@postDelayed
                if (mSliderController.isShowSystemUi) {
                    uiHelper.show()
                } else {
                    uiHelper.hide()
                }
            }
        }, 300)
    }

    // ======== Input delegation ========

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mInputHandler.handleKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mInputHandler.handleKeyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ======== GalleryView.Listener (delegated to helpers) ========

    override fun onUpdateCurrentIndex(index: Int) {
        // A transient layout with no page on screen reports INVALID_INDEX (-1)
        // (e.g. right after a data change or an error view). Persisting it would
        // store a negative page locally and push progress=0 ("unread") to the
        // server, clobbering real (possibly cross-device) progress. Skip
        // persistence for it but still forward to the slider below so the UI
        // tracks the state change.
        if (index >= 0) {
            val provider = mGalleryProvider
            provider?.putStartPage(index)
            // Persist intra-page fraction in lockstep with page-index
            // changes — this is the same cadence the existing local SP
            // page progress uses, plus an onPause() backstop. Vertical
            // mode only: pager modes always report 0 and would just
            // overwrite a saved fraction with 0 every time the user
            // crossed a page boundary.
            val gv = mGalleryView
            if (provider != null && gv != null
                && gv.layoutMode == GalleryView.LAYOUT_TOP_TO_BOTTOM) {
                provider.putScrollFraction(gv.currentScrollFraction)
            }
        }
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_CURRENT_INDEX, index)
        mainHandler.post(task)
    }

    override fun onTapSliderArea() {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_SLIDER_AREA, 0)
        mainHandler.post(task)
    }

    override fun onTapMenuArea() {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_MENU_AREA, 0)
        mainHandler.post(task)
    }

    override fun onTapErrorText(index: Int) {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_ERROR_TEXT, index)
        mainHandler.post(task)
    }

    override fun onLongPressPage(index: Int) {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_LONG_PRESS_PAGE, index)
        mainHandler.post(task)
    }

    override fun onAutoTransferDone() {
        mInputHandler.onAutoTransferDone()
    }

    // ======== GalleryMenuHelper.SettingsCallback ========

    override fun onSettingsApplied(
        screenRotation: Int, layoutMode: Int, scaleMode: Int,
        startPosition: Int, keepScreenOn: Boolean,
        showClock: Boolean, showProgress: Boolean, showBattery: Boolean,
        showPageInterval: Boolean, volumePage: Boolean,
        reverseVolumePage: Boolean, readingFullscreen: Boolean,
        customScreenLightness: Boolean, screenLightness: Int,
        transferTime: Int
    ) {
        val gv = mGalleryView ?: return

        val oldReadingFullscreen = ReadingSettings.getReadingFullscreen()

        requestedOrientation = resolveOrientation(screenRotation)
        gv.layoutMode = layoutMode
        gv.setScaleMode(scaleMode)
        gv.setStartPosition(startPosition)

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mClock?.visibility = if (showClock) View.VISIBLE else View.GONE
        mProgress?.visibility = if (showProgress) View.VISIBLE else View.GONE
        mBattery?.visibility = if (showBattery) View.VISIBLE else View.GONE

        gv.setPagerInterval(
            if (showPageInterval) resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) else 0
        )
        // See onCreateView for the rationale: long-strip mode runs
        // with no inter-page gap regardless of the user's
        // showPageInterval choice (which governs only the pager
        // mode's discrete-page separator).
        gv.setScrollInterval(0)

        setScreenLightness(customScreenLightness, screenLightness)

        mSliderController.layoutMode = layoutMode
        mInputHandler.layoutMode = layoutMode

        if (oldReadingFullscreen != readingFullscreen) {
            recreate()
        }
    }

    // ======== Screen lightness ========

    private fun setScreenLightness(enable: Boolean, lightness: Int) {
        val maskView = mMaskView ?: return
        val w = window
        val lp = w.attributes
        if (enable) {
            val clampedLightness = MathUtils.clamp(lightness, 0, 200)
            if (clampedLightness > 100) {
                maskView.setColor(0)
                lp.screenBrightness = Math.max((clampedLightness - 100) / 100.0f, 0.01f)
            } else {
                maskView.setColor(
                    MathUtils.lerp(0xde, 0x00, clampedLightness / 100.0f) shl 24
                )
                lp.screenBrightness = 0.01f
            }
        } else {
            maskView.setColor(0)
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        w.attributes = lp
    }

    // ======== NotifyTask (UI-thread dispatch) ========

    private inner class NotifyTask : Runnable {

        private var mKey = 0
        private var mValue = 0

        fun setData(key: Int, value: Int) {
            mKey = key
            mValue = value
        }

        private fun doTapMenuArea() {
            val builder = AlertDialog.Builder(this@GalleryActivity)
            val helper = GalleryMenuHelper(
                builder.context,
                this@GalleryActivity,
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            setScreenLightness(true, progress)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                }
            )
            val dialog = builder.setTitle(R.string.gallery_menu_title)
                .setView(helper.view)
                .show()
            mImageOps.applyImmersiveToDialog(dialog)
        }

        override fun run() {
            when (mKey) {
                NOTIFY_KEY_LAYOUT_MODE -> {
                    mSliderController.layoutMode = mValue
                    mInputHandler.layoutMode = mValue
                }
                NOTIFY_KEY_SIZE -> mSliderController.size = mValue
                NOTIFY_KEY_CURRENT_INDEX -> mSliderController.currentIndex = mValue
                NOTIFY_KEY_TAP_MENU_AREA -> doTapMenuArea()
                NOTIFY_KEY_TAP_SLIDER_AREA -> mSliderController.onTapSliderArea()
                NOTIFY_KEY_TAP_ERROR_TEXT -> mGalleryProvider?.forceRequest(mValue)
                NOTIFY_KEY_LONG_PRESS_PAGE -> mImageOps.showPageDialog(mValue)
            }
            mNotifyTaskPool.push(this)
        }
    }

    // ======== GalleryAdapter ========

    private inner class GalleryAdapter(
        glRootView: GLRootView,
        provider: GalleryProvider
    ) : SimpleAdapter(glRootView, provider) {

        override fun onDataChanged() {
            super.onDataChanged()

            val provider = mGalleryProvider ?: return
            val size = provider.size()
            var task = mNotifyTaskPool.pop()
            if (task == null) {
                task = NotifyTask()
            }
            task.setData(NOTIFY_KEY_SIZE, size)
            mainHandler.post(task)
        }

        override fun onPageSucceed(index: Int, image: com.hippo.lib.glview.image.ImageWrapper) {
            super.onPageSucceed(index, image)
            // Setting the page image doesn't by itself flip GalleryView
            // back into a fill cycle — it just paints the new texture
            // on the next render. Pending intra-page restores in
            // ScrollLayoutManager are gated on `target.isLoaded()`,
            // so without an explicit requestFill here they'd never
            // get a second chance after the target page's image
            // arrives async.
            mGalleryView?.requestFill()
        }
    }
}
