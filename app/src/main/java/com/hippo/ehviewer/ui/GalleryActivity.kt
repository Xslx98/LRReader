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
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.event.GalleryActivityEvent
import com.hippo.ehviewer.gallery.ArchiveGalleryProvider
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
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.lib.yorozuya.ViewUtils
import java.io.File
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
        const val KEY_GALLERY_INFO = "gallery_info"
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

    private var mAction: String? = null
    private var mFilename: String? = null
    private var mUri: android.net.Uri? = null
    private var mGalleryInfo: GalleryInfo? = null
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
                    val galleryInfo = mGalleryInfo
                    mGalleryProvider = if (galleryInfo != null) {
                        DirGalleryProvider(uniFile, this, galleryInfo)
                    } else {
                        DirGalleryProvider(uniFile)
                    }
                }
            }
            ACTION_LRR -> {
                val galleryInfo = mGalleryInfo
                if (galleryInfo != null) {
                    mGalleryProvider = LRRGalleryProvider(this, galleryInfo)
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
            mGalleryInfo = event.galleryInfo
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
        mGalleryInfo = intent.getParcelableExtra(KEY_GALLERY_INFO)
        val onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false)
        if (!onEvent) {
            canFinish = true
        }
        mPage = intent.getIntExtra(KEY_PAGE, -1)
        buildProvider()
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mAction = savedInstanceState.getString(KEY_ACTION)
        mFilename = savedInstanceState.getString(KEY_FILENAME)
        mUri = savedInstanceState.getParcelable(KEY_URI)
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO)
        mPage = savedInstanceState.getInt(KEY_PAGE, -1)
        mSliderController.currentIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX)
        buildProvider()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTION, mAction)
        outState.putString(KEY_FILENAME, mFilename)
        outState.putParcelable(KEY_URI, mUri)
        mGalleryInfo?.let { outState.putParcelable(KEY_GALLERY_INFO, it) }
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
        if (mGalleryProvider == null) {
            if (!canFinish) {
                return
            }
            finish()
            return
        }
        mGalleryProvider!!.start()

        // Get start page
        val startPage: Int = if (savedInstanceState == null) {
            if (mPage >= 0) mPage else mGalleryProvider!!.getStartPage()
        } else {
            mSliderController.currentIndex
        }

        if (!isEglAvailable()) {
            mGalleryProvider!!.stop()
            showGlFallbackView()
            return
        }

        setContentView(R.layout.activity_gallery)
        mGLRootView = ViewUtils.`$$`(this, R.id.gl_root_view) as GLRootView
        mGalleryAdapter = GalleryAdapter(mGLRootView!!, mGalleryProvider!!)
        val resources = resources
        mGalleryView = GalleryView.Builder(this, mGalleryAdapter!!)
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
            .setScrollInterval(
                if (ReadingSettings.getShowPageInterval())
                    resources.getDimensionPixelOffset(R.dimen.gallery_scroll_interval) else 0
            )
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
        mGLRootView!!.setContentPane(mGalleryView)
        mGLRootView!!.setOnGenericMotionListener(mInputHandler::handleGenericMotion)
        mGalleryProvider!!.galleryView = mGalleryView
        mGalleryProvider!!.setListener(mGalleryAdapter)
        mGalleryProvider!!.setGLRoot(mGLRootView)

        // Setup helpers
        mInputHandler.galleryView = mGalleryView
        mImageOps.galleryProvider = mGalleryProvider
        mImageOps.galleryInfo = mGalleryInfo

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
            mSystemUiHelper = SystemUiHelper(
                this, SystemUiHelper.LEVEL_IMMERSIVE,
                SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES or SystemUiHelper.FLAG_IMMERSIVE_STICKY
            )
            mSystemUiHelper!!.hide()
        }

        // Header views
        mMaskView = ViewUtils.`$$`(this, R.id.mask) as ColorView
        mClock = ViewUtils.`$$`(this, R.id.clock)
        mProgress = ViewUtils.`$$`(this, R.id.progress) as TextView
        mBattery = ViewUtils.`$$`(this, R.id.battery)
        mClock!!.visibility = if (ReadingSettings.getShowClock()) View.VISIBLE else View.GONE
        mProgress!!.visibility = if (ReadingSettings.getShowProgress()) View.VISIBLE else View.GONE
        mBattery!!.visibility = if (ReadingSettings.getShowBattery()) View.VISIBLE else View.GONE

        // Slider controller
        val seekBarPanel = ViewUtils.`$$`(this, R.id.seek_bar_panel)
        val autoTransferPanel = ViewUtils.`$$`(this, R.id.auto_transfer) as ImageView
        val leftText = ViewUtils.`$$`(seekBarPanel, R.id.left) as TextView
        val rightText = ViewUtils.`$$`(seekBarPanel, R.id.right) as TextView
        val seekBar = ViewUtils.`$$`(seekBarPanel, R.id.seek_bar) as ReversibleSeekBar

        mSliderController.setViews(
            seekBarPanel, autoTransferPanel, leftText, rightText, seekBar, mProgress as TextView
        )
        mSliderController.setSystemUiHelper(mSystemUiHelper)
        mSliderController.setGalleryView(mGalleryView)

        mInputHandler.autoTransferPanel = autoTransferPanel
        autoTransferPanel.setOnClickListener { v -> mInputHandler.toggleAutoRead(v) }

        val size = mGalleryProvider!!.size()
        mSliderController.size = size
        if (savedInstanceState == null) {
            mSliderController.currentIndex = startPage
        }
        if (mGalleryView != null) {
            mSliderController.layoutMode = mGalleryView!!.layoutMode
            mInputHandler.layoutMode = mGalleryView!!.layoutMode
        }

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

        mGLRootView = null
        mGalleryView = null
        if (mGalleryAdapter != null) {
            mGalleryAdapter!!.clearUploader()
            mGalleryAdapter = null
        }
        if (mGalleryProvider != null) {
            mGalleryProvider!!.setListener(null)
            mGalleryProvider!!.stop()
            mGalleryProvider = null
        }

        mMaskView = null
        mClock = null
        mProgress = null
        mBattery = null

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent()
        intent.putExtra("info", mGalleryInfo)
        setResult(DownloadsScene.LOCAL_GALLERY_INFO_CHANGE, intent)
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        mGLRootView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        mGLRootView?.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        SimpleHandler.getInstance().postDelayed({
            if (hasFocus && mSystemUiHelper != null) {
                if (mSliderController.isShowSystemUi) {
                    mSystemUiHelper!!.show()
                } else {
                    mSystemUiHelper!!.hide()
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
        mGalleryProvider?.putStartPage(index)
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_CURRENT_INDEX, index)
        SimpleHandler.getInstance().post(task)
    }

    override fun onTapSliderArea() {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_SLIDER_AREA, 0)
        SimpleHandler.getInstance().post(task)
    }

    override fun onTapMenuArea() {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_MENU_AREA, 0)
        SimpleHandler.getInstance().post(task)
    }

    override fun onTapErrorText(index: Int) {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_TAP_ERROR_TEXT, index)
        SimpleHandler.getInstance().post(task)
    }

    override fun onLongPressPage(index: Int) {
        var task = mNotifyTaskPool.pop()
        if (task == null) {
            task = NotifyTask()
        }
        task.setData(NOTIFY_KEY_LONG_PRESS_PAGE, index)
        SimpleHandler.getInstance().post(task)
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
        if (mGalleryView == null) return

        val oldReadingFullscreen = ReadingSettings.getReadingFullscreen()

        requestedOrientation = resolveOrientation(screenRotation)
        mGalleryView!!.layoutMode = layoutMode
        mGalleryView!!.setScaleMode(scaleMode)
        mGalleryView!!.setStartPosition(startPosition)

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mClock?.visibility = if (showClock) View.VISIBLE else View.GONE
        mProgress?.visibility = if (showProgress) View.VISIBLE else View.GONE
        mBattery?.visibility = if (showBattery) View.VISIBLE else View.GONE

        mGalleryView!!.setPagerInterval(
            if (showPageInterval) resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) else 0
        )
        mGalleryView!!.setScrollInterval(
            if (showPageInterval) resources.getDimensionPixelOffset(R.dimen.gallery_scroll_interval) else 0
        )

        setScreenLightness(customScreenLightness, screenLightness)

        mSliderController.layoutMode = layoutMode
        mInputHandler.layoutMode = layoutMode

        if (oldReadingFullscreen != readingFullscreen) {
            recreate()
        }
    }

    // ======== Screen lightness ========

    private fun setScreenLightness(enable: Boolean, lightness: Int) {
        if (mMaskView == null) {
            return
        }
        val w = window
        val lp = w.attributes
        if (enable) {
            val clampedLightness = MathUtils.clamp(lightness, 0, 200)
            if (clampedLightness > 100) {
                mMaskView!!.setColor(0)
                lp.screenBrightness = Math.max((clampedLightness - 100) / 100.0f, 0.01f)
            } else {
                mMaskView!!.setColor(
                    MathUtils.lerp(0xde, 0x00, clampedLightness / 100.0f) shl 24
                )
                lp.screenBrightness = 0.01f
            }
        } else {
            mMaskView!!.setColor(0)
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

            if (mGalleryProvider != null) {
                val size = mGalleryProvider!!.size()
                var task = mNotifyTaskPool.pop()
                if (task == null) {
                    task = NotifyTask()
                }
                task.setData(NOTIFY_KEY_SIZE, size)
                SimpleHandler.getInstance().post(task)
            }
        }
    }
}
