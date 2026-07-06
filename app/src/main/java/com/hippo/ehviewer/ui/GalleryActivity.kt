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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.BuildConfig
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
import com.hippo.ehviewer.gallery.NextArchiveResolver
import com.hippo.ehviewer.ui.gallery.GalleryImageOperations
import com.hippo.ehviewer.ui.gallery.GalleryInputHandler
import com.hippo.ehviewer.ui.gallery.GalleryMenuHelper
import com.hippo.ehviewer.ui.gallery.GallerySliderController
import com.hippo.ehviewer.ui.gallery.GalleryStampOps
import com.hippo.ehviewer.ui.gallery.LRRStampsBackend
import com.hippo.ehviewer.ui.gallery.ReaderContinuationController
import com.hippo.ehviewer.ui.gallery.ReaderStampsController
import com.hippo.ehviewer.ui.gallery.TankoubonProgressSync
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.widget.GalleryGuideView
import com.hippo.ehviewer.widget.GalleryHeader
import com.hippo.ehviewer.widget.ReversibleSeekBar
import com.hippo.ehviewer.widget.StampOverlayView
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
import kotlin.coroutines.cancellation.CancellationException
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
        private const val NOTIFY_KEY_PAGE_TRANSFORMS = 7

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

    private var mContinuation: ReaderContinuationController? = null

    private var mTankProgress: TankoubonProgressSync? = null

    private var mStamps: ReaderStampsController? = null
    private var mStampOverlay: StampOverlayView? = null
    private var mStampOps: GalleryStampOps? = null

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
                        DirGalleryProvider(
                            uniFile, this, archive.arcid, archive.serverProfileId
                        ).also {
                            it.expectedPageCount = archive.pagecount
                        }
                    } else {
                        DirGalleryProvider(uniFile)
                    }
                }
            }
            ACTION_LRR -> {
                val archive = mArchive
                if (archive != null) {
                    mGalleryProvider = LRRGalleryProvider(
                        this, archive.arcid, archive.serverProfileId
                    )
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = mUri
                if (uri != null) {
                    mGalleryProvider = ArchiveGalleryProvider(this, uri)
                }
            }
        }
        // KEY_PAGE override (e.g. a detail-page thumbnail tap) so the
        // provider warms / consumes the decoded slot for the page the
        // reader actually opens on. -1 = use saved progress.
        mGalleryProvider?.initialPageOverride = mPage
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

    /**
     * singleTask (AndroidManifest): a launch that arrives while this
     * instance is alive lands here instead of onCreate — previously it
     * was silently dropped and the old archive/page just came to the
     * foreground (RD-7). Re-initializing in place would require a full
     * provider/GL teardown, so reuse the continuation pattern instead
     * (see [launchNextArchive]): mark this instance finishing, then
     * launch a fresh instance carrying the new intent through the
     * proven cold-start path.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
        startActivity(intent)
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

        // End-of-book continuation. The scrim only exists once onCreateView
        // reached setContentView(R.layout.activity_gallery) — it is absent on
        // the GL-fallback layout and on the finish-early (no provider) path,
        // hence the nullable lookup. Runs after both init paths (intent /
        // sticky event) so mArchive is final here.
        findViewById<View>(R.id.continuation_scrim)?.let { scrim ->
            mContinuation = ReaderContinuationController(
                root = scrim,
                scope = lifecycleScope,
                resolver = NextArchiveResolver(ServiceRegistry.networkModule.okHttpClient),
                launchNext = { next -> launchNextArchive(next) },
            )
            mArchive?.let { mContinuation?.setCurrentArchive(it.arcid) }
        }

        // Tank-level progress sync. Deliberately outside the scrim block: a
        // GL-fallback layout without the continuation overlay must still sync
        // tank progress.
        mArchive?.let { mTankProgress = TankoubonProgressSync(it.arcid) }

        // Stamp overlay read path. Requires mArchive (server-backed archive
        // identity) — the legacy local-file DIR path without an archive gets
        // no controller and the overlay stays gone. mArchive is final here
        // (see the continuation-block comment above).
        mStampOverlay = findViewById(R.id.stamp_overlay)
        mArchive?.let { archive ->
            val stamps = ReaderStampsController(
                scope = lifecycleScope,
                backend = LRRStampsBackend(archive.arcid, archive.serverProfileId),
                onDataChanged = { onStampsDataChanged() },
            )
            mStamps = stamps
            mStampOverlay?.stampsProvider = { page0 -> stamps.stampsForDisplayPage(page0) }
            mStampOverlay?.let { overlay ->
                val ops = GalleryStampOps(this, stamps, overlay)
                overlay.callback = ops
                mStampOps = ops
            }
            refreshStampsVisibility()
        }
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
        mContinuation = null
        mStampOps?.dismissCard()
        mStampOps = null
        mStamps = null
        mStampOverlay = null

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mStampOverlay?.placing == true) {
            mStampOps?.exitPlacementMode()
            return
        }
        if (mContinuation?.isShowing == true) {
            mContinuation?.hide()
            return
        }
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

    override fun onPageTransformsChanged() {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_PAGE_TRANSFORMS, 0)
        mainHandler.post(task)
    }

    override fun onAutoTransferDone() {
        mInputHandler.onAutoTransferDone()
        // Fires on the GL render thread: on a forward page-turn attempt at
        // the last page (pager modes), on BOTTOM over-scroll at the end of
        // the strip (vertical mode) — but ALSO on TOP over-scroll at the
        // first page in vertical mode, which atLastPage filters out.
        // isReachEnd() reads render-thread layout state — same thread here,
        // so the read is safe. Unlike currentIndex (the first VISIBLE page),
        // it stays accurate in scroll mode when a tall page dominates the
        // viewport and the short last page is merely attached below it.
        val gv = mGalleryView ?: return
        val atEnd = gv.isReachEnd
        mainHandler.post {
            mContinuation?.onEndOfBookEvent(atLastPage = atEnd)
        }
    }

    /**
     * Launch the next archive picked by the continuation flow and finish this
     * reader so back returns to the originating list, not a reader stack.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun launchNextArchive(next: Archive) {
        lifecycleScope.launch {
            try {
                val intent = GalleryOpenHelper.buildReadIntent(this@GalleryActivity, next)
                // singleTask (AndroidManifest): a live top instance would
                // swallow the intent via onNewIntent (not overridden). Mark
                // this instance finishing FIRST so the system creates a fresh
                // instance for the next archive. finish() must be immediately
                // followed by startActivity with no suspension in between —
                // the lifecycleScope coroutine would be cancelled once the
                // activity is destroyed.
                finish()
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("GalleryActivity", "continuation launch failed", e)
                Toast.makeText(this@GalleryActivity, R.string.continuation_error, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    // ======== Reader stamps ========

    internal fun refreshStampsVisibility() {
        val overlay = mStampOverlay ?: return
        val stamps = mStamps
        val visible = stamps != null &&
            stamps.support != ReaderStampsController.Support.UNSUPPORTED &&
            (ReadingSettings.getReaderStamps() || stamps.sessionVisible)
        overlay.isVisible = visible
        if (visible) {
            pumpStampTransforms()
        }
    }

    private fun onStampsDataChanged() {
        // A support probe may resolve to UNSUPPORTED while the user is mid
        // placement (e.g. the pre-0.9.8 404 lands after the menu tap). Kill
        // the mode here — the overlay would otherwise keep eating touches
        // while refreshStampsVisibility() hides it. Fires at most once:
        // exitPlacementMode() clears `placing`.
        if (mStamps?.support == ReaderStampsController.Support.UNSUPPORTED &&
            mStampOverlay?.placing == true
        ) {
            mStampOps?.exitPlacementMode()
            Toast.makeText(this, R.string.stamps_unsupported, Toast.LENGTH_LONG).show()
        }
        refreshStampsVisibility()
        mStampOverlay?.invalidate()
    }

    private fun pumpStampTransforms() {
        val overlay = mStampOverlay ?: return
        val transforms = mGalleryView?.getPageTransforms().orEmpty()
        overlay.transforms = transforms
        mStamps?.ensureVisiblePagesLoaded(transforms.map { it.index })
    }

    internal fun areStampsAvailable(): Boolean = mStampOps?.isAvailable() == true

    internal fun startStampPlacement() {
        mStampOps?.startPlacementMode()
    }

    internal fun jumpToPage(page0: Int) {
        mGalleryView?.setCurrentPage(page0)
    }

    internal fun showStampedPagesDialog() {
        mStampOps?.showStampedPagesDialog()
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

        refreshStampsVisibility()
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
                NOTIFY_KEY_CURRENT_INDEX -> {
                    mSliderController.currentIndex = mValue
                    // A page turn invalidates the floating stamp card's anchor,
                    // so drop it here (fires per index change, not per animation
                    // frame). Accepted residual: zoom/pan on the SAME page keeps
                    // the card without tracking the marker — it self-heals on
                    // outside tap.
                    mStampOps?.dismissCard()
                    // Reaching the last page silently warms the next-archive
                    // resolution so the continuation panel (or the
                    // swipe-through jump) is instant on the first forward
                    // attempt instead of showing a loading state.
                    mContinuation?.onPageShown(mValue, mGalleryProvider?.size() ?: 0)
                    mTankProgress?.onPageShown(mValue)
                }
                NOTIFY_KEY_TAP_MENU_AREA -> doTapMenuArea()
                NOTIFY_KEY_TAP_SLIDER_AREA -> mSliderController.onTapSliderArea()
                NOTIFY_KEY_TAP_ERROR_TEXT -> mGalleryProvider?.forceRequest(mValue)
                NOTIFY_KEY_LONG_PRESS_PAGE -> mImageOps.showPageDialog(mValue)
                NOTIFY_KEY_PAGE_TRANSFORMS -> {
                    if (mStampOverlay?.isVisible == true) pumpStampTransforms()
                }
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
