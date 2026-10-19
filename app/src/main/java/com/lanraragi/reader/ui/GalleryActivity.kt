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

package com.lanraragi.reader.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.settings.GuideSettings
import com.lanraragi.reader.settings.ReadingSettings
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.GalleryActivityEvent
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.ForegroundReading
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.LRRGalleryProvider
import com.lanraragi.reader.gallery.NextArchiveResolver
import com.lanraragi.reader.gallery.ReadingSessionTracker
import com.lanraragi.reader.gallery.TankGalleryProvider
import com.lanraragi.reader.ui.gallery.GalleryImageOperations
import com.lanraragi.reader.ui.gallery.GalleryInputHandler
import com.lanraragi.reader.ui.gallery.GalleryMenuHelper
import com.lanraragi.reader.ui.gallery.GallerySliderController
import com.lanraragi.reader.ui.gallery.ReaderChromeController
import com.lanraragi.reader.ui.gallery.ReaderContinuationController
import com.lanraragi.reader.ui.gallery.ReaderGalleryViews
import com.lanraragi.reader.ui.gallery.ReaderLaunch
import com.lanraragi.reader.ui.gallery.ReaderPageHost
import com.lanraragi.reader.ui.gallery.ReaderSessionSetup
import com.lanraragi.reader.ui.gallery.ReaderStampsCoordinator
import com.lanraragi.reader.ui.gallery.TankoubonProgressSync
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import com.lanraragi.reader.widget.GalleryGuideView
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.framework.util.BackDispatch
import com.lanraragi.framework.lib.yorozuya.ConcurrentPool
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

class GalleryActivity : BaseActivity(), GalleryView.Listener,
    GalleryInputHandler.Callback, GalleryMenuHelper.SettingsCallback {

    companion object {
        const val ACTION_DIR = "dir"
        const val ACTION_LRR = "lrr"

        /** Whole-tankoubon composite session (see TankGalleryProvider). */
        const val ACTION_TANK = "tank"

        const val KEY_ACTION = "action"

        /** Parcelable [com.lanraragi.reader.gallery.TankSessionSeed] for [ACTION_TANK]. */
        const val KEY_TANK_SEED = "tank_seed"
        const val KEY_FILENAME = "filename"
        /**
         * ACTION_LRR only: absolute path of a partial local download for this
         * archive. Puts [LRRGalleryProvider] in hybrid mode — read landed
         * pages from the download directory, write fetched ones back there.
         */
        const val KEY_DOWNLOAD_DIR = "download_dir"
        const val KEY_ARCHIVE = "archive"

        /** onBackPressed result-extra carrying the (possibly mutated) archive back to the launching scene. */
        const val EXTRA_RESULT_ARCHIVE = "result_archive"
        const val DATA_IN_EVENT = "data_in_event"
        const val KEY_PAGE = "page"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_PENDING_SAVE_FILE = "pending_save_file"

        // NotifyTask keys
        private const val NOTIFY_KEY_LAYOUT_MODE = 0
        private const val NOTIFY_KEY_SIZE = 1
        private const val NOTIFY_KEY_CURRENT_INDEX = 2
        private const val NOTIFY_KEY_TAP_SLIDER_AREA = 3
        private const val NOTIFY_KEY_TAP_MENU_AREA = 4
        private const val NOTIFY_KEY_TAP_ERROR_TEXT = 5
        private const val NOTIFY_KEY_LONG_PRESS_PAGE = 6
        private const val NOTIFY_KEY_PAGE_TRANSFORMS = 7
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mLaunch = ReaderLaunch()
    private val mReadingSession = ReadingSessionTracker()

    private val mPageHost = ReaderPageHost(this)
    private val mGalleryView: GalleryView?
        get() = mPageHost.galleryView
    private var mGalleryProvider: GalleryProvider2? = null


    private val mChrome = ReaderChromeController(this)

    private var canFinish = false

    private var mContinuation: ReaderContinuationController? = null

    private var mTankProgress: TankoubonProgressSync? = null

    private val mStampsCoordinator = ReaderStampsCoordinator(this) { mGalleryView }

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

        mGalleryProvider = mLaunch.createProvider(this)
        // KEY_PAGE override (e.g. a detail-page thumbnail tap) so the
        // provider warms / consumes the decoded slot for the page the
        // reader actually opens on. -1 = use saved progress.
        mGalleryProvider?.initialPageOverride = mLaunch.page
    }

    // ======== Sticky event ========

    private fun consumeStickyGalleryEvent() {
        if (mGalleryProvider != null) {
            return
        }
        val cache = AppEventBus.galleryActivityEvent.replayCache
        if (cache.isNotEmpty()) {
            val event = cache[cache.size - 1]
            mLaunch.archive = event.archive
            mLaunch.page = event.pagePosition
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

        val onEvent = mLaunch.readIntent(intent)
        if (!onEvent) {
            canFinish = true
        }
        buildProvider()
        ReaderSessionSetup.recordHistory(mLaunch.archive, mLaunch.tankSeed)
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mLaunch.restore(savedInstanceState)
        mSliderController.currentIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX)
        mImageOps.pendingSaveFileName = savedInstanceState.getString(KEY_PENDING_SAVE_FILE)
        buildProvider()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mLaunch.save(outState)
        outState.putInt(KEY_CURRENT_INDEX, mSliderController.currentIndex)
        outState.putString(KEY_PENDING_SAVE_FILE, mImageOps.pendingSaveFileName)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        if (ReadingSettings.getReadingFullscreen()) {
            mChrome.applyFullscreenWindowFlags()
        } else {
            mChrome.showSystemBarsLeftByFullscreen()
        }
        super.onCreate(savedInstanceState)
        // Locked: BaseActivity handed off to the lock screen (this intent is
        // stashed to resume after unlock). Build nothing.
        if (isFinishing) return
        onBackPressedDispatcher.addCallback(this, backCallback)
        // StrictMode policies are installed app-wide (debug only) in
        // LRReaderApplication. The block that used to sit here installed an EMPTY
        // VmPolicy (detectFileUriExposure() was called after build()) — a
        // no-op that also cleared any inherited policy, in release too.

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
        // sticky event) so mLaunch.archive is final here.
        findViewById<View>(R.id.continuation_scrim)?.let { scrim ->
            mContinuation = ReaderContinuationController(
                root = scrim,
                scope = lifecycleScope,
                resolver = NextArchiveResolver(ServiceRegistry.networkModule.okHttpClient),
                launchNext = { next -> launchNextArchive(next) },
            )
            mLaunch.archive?.let { mContinuation?.setCurrentArchive(it.arcid) }
        }

        // Tank-level progress sync. Deliberately outside the scrim block: a
        // GL-fallback layout without the continuation overlay must still sync
        // tank progress.
        mLaunch.archive?.let { mTankProgress = TankoubonProgressSync(it.arcid) }

        // Stamp overlay. mLaunch.archive is final here (see the continuation-block
        // comment above).
        mStampsCoordinator.setUp(lifecycleScope, mLaunch.archive, mLaunch.tankSeed, mGalleryProvider)
    }

    private fun onCreateView(savedInstanceState: Bundle?) {
        val galleryProvider = mGalleryProvider ?: run {
            if (canFinish) finish()
            return
        }
        galleryProvider.start()

        // Get start page
        val startPage: Int = if (savedInstanceState == null) {
            if (mLaunch.page >= 0) mLaunch.page else galleryProvider.getStartPage()
        } else {
            mSliderController.currentIndex
        }

        if (!ReaderGalleryViews.isEglAvailable()) {
            galleryProvider.stop()
            showGlFallbackView()
            return
        }

        setContentView(R.layout.activity_gallery)
        val galleryView = mPageHost.attach(
            galleryProvider, this, startPage, mInputHandler::handleGenericMotion
        ) {
            mGalleryProvider?.let { postNotify(NOTIFY_KEY_SIZE, it.size()) }
        }

        // Setup helpers
        mInputHandler.galleryView = galleryView
        mImageOps.galleryProvider = galleryProvider
        mImageOps.archive = mLaunch.archive
        // Tank sessions keep mLaunch.archive null (composite reader invariant); an
        // archive session always has one, including downloaded copies read
        // offline. A bare directory session has no server to write to.
        mImageOps.coverTarget = mLaunch.tankSeed?.let {
            GalleryImageOperations.CoverTarget.Tank(it.tankId, it.profileId)
        } ?: mLaunch.archive?.let {
            GalleryImageOperations.CoverTarget.Archive(it.arcid, it.serverProfileId)
        }

        mChrome.setUpSystemUi()
        val progress = mChrome.bindHeader()

        mChrome.bindSlider(mSliderController, mInputHandler, galleryView, progress)

        val size = galleryProvider.size()
        mSliderController.size = size
        if (savedInstanceState == null) {
            mSliderController.currentIndex = startPage
        }
        mSliderController.layoutMode = galleryView.layoutMode
        mInputHandler.layoutMode = galleryView.layoutMode

        // Reading-session seam: consumed by the continue-reading shortcut and
        // the daily reading aggregate. DIR-mode reading (no Archive) stays
        // untracked — without start(), stop() in onStop is a no-op. A tank
        // session tracks as ONE logical archive keyed by the tank id (global
        // pages; completion = crossing the tank's end) — tank-only
        // bookkeeping, and the shortcut/widget deep link resumes the
        // composite session via MainActivity's isTankoubonId branch.
        mLaunch.archive?.let {
            mReadingSession.start(it.arcid, it.serverProfileId, startPage, it.pagecount)
        } ?: mLaunch.tankSeed?.let { seed ->
            mReadingSession.start(
                seed.tankId, seed.profileId, startPage,
                (mGalleryProvider?.size() ?: 0).coerceAtLeast(0),
            )
        }

        mChrome.applyInitialWindowSettings()

        if (GuideSettings.getGuideGallery()) {
            val mainLayout = ViewUtils.`$$`(this, R.id.main) as FrameLayout
            mainLayout.addView(GalleryGuideView(this))
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

        mPageHost.detach()
        mGalleryProvider?.let { provider ->
            provider.setListener(null)
            provider.stop()
        }
        mGalleryProvider = null

        mChrome.clear()
        mContinuation = null
        mStampsCoordinator.clear()

        super.onDestroy()
    }

    // Always enabled: leaving the reader must carry the result archive back.
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = handleBack()
    }

    internal fun handleBack() {
        if (mStampsCoordinator.isPlacing) {
            mStampsCoordinator.exitPlacementMode()
        } else if (mContinuation?.isShowing == true) {
            mContinuation?.hide()
        } else {
            val intent = Intent()
            intent.putExtra(EXTRA_RESULT_ARCHIVE, mLaunch.archive)
            setResult(DownloadsScene.LOCAL_GALLERY_INFO_CHANGE, intent)
            BackDispatch.passThrough(onBackPressedDispatcher, backCallback)
        }
    }

    override fun onStart() {
        super.onStart()
        // Background downloads yield to the page on screen (audit PERF-13).
        ForegroundReading.enter()
    }

    override fun onStop() {
        super.onStop()
        ForegroundReading.exit()
        val currentPage = mSliderController.currentIndex.takeIf { it >= 0 } ?: mLaunch.page
        mReadingSession.stop(currentPage)
    }

    override fun onPause() {
        super.onPause()
        val provider = mGalleryProvider
        mPageHost.saveScrollFraction(provider)
        // The local position is written at most once a second; write the latest
        // now, before the screen we return to reads it.
        provider?.flushLocalProgress()
        // Suspend auto page-turn while backgrounded; resumed in onResume().
        mInputHandler.pauseAutoRead()
        mPageHost.onPause()
    }

    override fun onResume() {
        super.onResume()
        // BaseActivity.onForegroundLockCheck may have launched MainActivity for
        // the security re-prompt — when CLEAR_TOP pops us, we end up
        // finishing. Don't touch GL resources on a doomed activity.
        if (isFinishing) return
        mPageHost.onResume()
        mInputHandler.resumeAutoRead()
    }


    /** Reopen the reader on the page it is showing once the lock is passed. */
    override fun lockResumeIntent(): Intent? = buildResumeIntent()

    private fun buildResumeIntent(): Intent? {
        val original = intent ?: return null
        // Before onCreate finished (redirected straight from onCreate) there
        // is no current page yet: keep the page the original intent asked for.
        val currentPage = mSliderController.currentIndex.takeIf { it >= 0 }
            ?: mLaunch.page.takeIf { mGalleryProvider != null }
            ?: original.getIntExtra(KEY_PAGE, -1)
        return ReaderLaunch.resumeIntent(this, original, currentPage)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        mainHandler.postDelayed({
            if (hasFocus) applySystemUiState()
        }, 300)
    }

    private fun applySystemUiState() {
        mChrome.applySystemUiState(mSliderController.isShowSystemUi)
    }

    /**
     * Rotation, fold/unfold, multi-window resizes and keyboard attach arrive
     * here instead of recreating the reader (AndroidManifest configChanges,
     * audit PERF-02): a recreation ran provider.stop(), dropping the decoded
     * pages and every GL texture, and an online session redid its metadata
     * request. What follows the new size without help:
     * - GLRootView.onLayout relays its new bounds to GalleryView, whose
     *   layout managers refill the pages and recompute the tap areas; the
     *   GL surface is resized, not recreated, so the EGL context stays.
     * - GalleryHeader re-reads the moved display cutout from the insets.
     * - Image.initialize re-reads the display metrics for decode sampling
     *   (LRReaderApplication.onConfigurationChanged). Decoded pages are kept:
     *   the fit / fit-width / fit-height samples hold for both orientations
     *   (Image.readerSampleSize), so a rotation never shows them below 1x.
     * - A user-locked orientation is a requestedOrientation, so the system
     *   never rotates the window in the first place.
     * The reader layouts have no orientation-qualified resources; the menu
     * dialog's keyline padding (values-sw600dp) refreshes when it is reopened.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The floating stamp card is anchored in screen pixels of the old layout.
        mStampsCoordinator.dismissCard()
        // Some devices re-show the system bars on rotation; restore the
        // immersive state the slider last asked for.
        applySystemUiState()
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
            mPageHost.saveScrollFraction(provider)
        }
        postNotify(NOTIFY_KEY_CURRENT_INDEX, index)
    }

    override fun onTapSliderArea() {
        postNotify(NOTIFY_KEY_TAP_SLIDER_AREA, 0)
    }

    override fun onTapMenuArea() {
        postNotify(NOTIFY_KEY_TAP_MENU_AREA, 0)
    }

    override fun onTapErrorText(index: Int) {
        postNotify(NOTIFY_KEY_TAP_ERROR_TEXT, index)
    }

    override fun onLongPressPage(index: Int) {
        postNotify(NOTIFY_KEY_LONG_PRESS_PAGE, index)
    }

    override fun onPageTransformsChanged() {
        postNotify(NOTIFY_KEY_PAGE_TRANSFORMS, 0)
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
        mStampsCoordinator.refreshVisibility()
    }

    internal fun areStampsAvailable(): Boolean = mStampsCoordinator.isAvailable()

    internal fun startStampPlacement() {
        mStampsCoordinator.startPlacement()
    }

    internal fun jumpToPage(page0: Int) {
        mGalleryView?.setCurrentPage(page0)
    }

    internal fun showStampedPagesDialog() {
        mStampsCoordinator.showStampedPagesDialog()
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

        mChrome.applySettings(screenRotation, keepScreenOn, showClock, showProgress, showBattery)
        mPageHost.applySettings(mGalleryProvider, layoutMode, scaleMode, startPosition, showPageInterval)

        mChrome.setScreenLightness(customScreenLightness, screenLightness)

        mSliderController.layoutMode = layoutMode
        mInputHandler.layoutMode = layoutMode

        refreshStampsVisibility()
    }

    override fun onReadingFullscreenChanged() {
        // The fullscreen window flags and SystemUiHelper are set up in
        // onCreate only. An explicit recreate() is not suppressed by the
        // manifest configChanges, which cover system config changes alone.
        recreate()
    }

    // ======== NotifyTask (UI-thread dispatch) ========

    /** Runs [key] with [value] on the UI thread through a pooled [NotifyTask]. */
    private fun postNotify(key: Int, value: Int) {
        val task = mNotifyTaskPool.pop() ?: NotifyTask()
        task.setData(key, value)
        mainHandler.post(task)
    }

    private inner class NotifyTask : Runnable {

        private var mKey = 0
        private var mValue = 0

        fun setData(key: Int, value: Int) {
            mKey = key
            mValue = value
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
                    mStampsCoordinator.dismissCard()
                    // Reaching the last page silently warms the next-archive
                    // resolution so the continuation panel (or the
                    // swipe-through jump) is instant on the first forward
                    // attempt instead of showing a loading state.
                    mContinuation?.onPageShown(mValue, mGalleryProvider?.size() ?: 0)
                    mTankProgress?.onPageShown(mValue)
                }
                NOTIFY_KEY_TAP_MENU_AREA ->
                    mChrome.showSettingsDialog(this@GalleryActivity, mImageOps::applyImmersiveToDialog)
                NOTIFY_KEY_TAP_SLIDER_AREA -> mSliderController.onTapSliderArea()
                NOTIFY_KEY_TAP_ERROR_TEXT -> mGalleryProvider?.forceRequest(mValue)
                NOTIFY_KEY_LONG_PRESS_PAGE -> mImageOps.showPageDialog(mValue)
                NOTIFY_KEY_PAGE_TRANSFORMS -> mStampsCoordinator.onPageTransformsChanged()
            }
            mNotifyTaskPool.push(this)
        }
    }
}
