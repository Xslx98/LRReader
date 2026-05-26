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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.util.Log
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.os.Process
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.AppLockGate
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.settings.NetworkSettings
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.callBack.ImageChangeCallBack
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.module.AppModule
import com.hippo.ehviewer.util.ImageDecodeUtils
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.ui.main.UserImageChange
import com.hippo.ehviewer.ui.scene.AnalyticsScene
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.ServerConfigScene
import com.hippo.ehviewer.ui.scene.ServerListScene
import com.hippo.ehviewer.ui.scene.download.DownloadLabelsScene
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.ui.scene.LRRCategoriesScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.ui.scene.history.HistoryScene
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene
import com.hippo.ehviewer.ui.scene.SecurityScene
import com.hippo.ehviewer.ui.scene.SolidScene
import com.hippo.ehviewer.ui.splash.SplashActivity
import com.hippo.ehviewer.client.LRRUrlOpener
import com.hippo.ehviewer.widget.EhDrawerLayout
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.network.Network
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFactory
import com.hippo.scene.SceneFragment
import com.hippo.scene.StageActivity
import com.hippo.unifile.UniFile
import com.hippo.util.BitmapUtils
import com.hippo.util.GifHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.widget.AvatarImageView
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.ResourcesUtils
import com.hippo.lib.yorozuya.ViewUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.updater.GhRelease
import com.hippo.ehviewer.ui.dialog.UpdateDialog

class MainActivity : StageActivity(),
    NavigationView.OnNavigationItemSelectedListener, ImageChangeCallBack, DrawerLayout.DrawerListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_NAV_CHECKED_ITEM = "nav_checked_item"
        /** Max dimension for user avatar/background decode to prevent OOM. */
        private const val MAX_IMAGE_DIMENSION = 1024

        init {
            registerLaunchMode(SecurityScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)

            registerLaunchMode(AnalyticsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(ServerConfigScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(ServerListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(GalleryListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(QuickSearchScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(GalleryDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)

            registerLaunchMode(DownloadsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(DownloadLabelsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(LRRCategoriesScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(HistoryScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)

            // Scene factory registrations (replaces reflection-based newInstance())
            SceneFactory.register(SecurityScene::class.java.name) { SecurityScene() }
            SceneFactory.register(AnalyticsScene::class.java.name) { AnalyticsScene() }
            SceneFactory.register(ServerConfigScene::class.java.name) { ServerConfigScene() }
            SceneFactory.register(ServerListScene::class.java.name) { ServerListScene() }
            SceneFactory.register(GalleryListScene::class.java.name) { GalleryListScene() }
            SceneFactory.register(QuickSearchScene::class.java.name) { QuickSearchScene() }
            SceneFactory.register(GalleryDetailScene::class.java.name) { GalleryDetailScene() }
            SceneFactory.register(DownloadsScene::class.java.name) { DownloadsScene() }
            SceneFactory.register(DownloadLabelsScene::class.java.name) { DownloadLabelsScene() }
            SceneFactory.register(LRRCategoriesScene::class.java.name) { LRRCategoriesScene() }
            SceneFactory.register(HistoryScene::class.java.name) { HistoryScene() }
        }
    }

    /*---------------
     Whole life cycle
     ---------------*/
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mDrawerLayout: EhDrawerLayout? = null
    private var mNavView: NavigationView? = null
    private var mRightDrawer: FrameLayout? = null
    private var mAvatar: AvatarImageView? = null
    private var mHeaderBackground: ImageView? = null
    private var mDisplayName: TextView? = null
    private var userImageChange: UserImageChange? = null

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshTopScene()
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        userImageChange?.handleCameraResult(result.resultCode, mAvatar)
    }

    private val albumLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        userImageChange?.handleAlbumResult(result.resultCode, result.data, mAvatar)
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        userImageChange?.handleCropResult(result.resultCode, result.data, mAvatar)
    }

    private var mNavCheckedItem = 0

    @JvmField
    var gifHandler: GifHandler? = null

    @JvmField
    var backgroundBit: Bitmap? = null

    private val handlerB: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val gif = gifHandler ?: return
            val bg = backgroundBit ?: return
            if (bg.isRecycled) return
            val mNextFrame = gif.updateFrame(bg)
            if (mNextFrame > 0) {
                sendEmptyMessageDelayed(1, mNextFrame.toLong())
            }
            mHeaderBackground?.setImageBitmap(bg)
        }
    }

    override fun getThemeResId(theme: Int): Int = when (theme) {
        AppearanceSettings.THEME_DARK -> R.style.AppTheme_Main_Dark
        AppearanceSettings.THEME_BLACK -> R.style.AppTheme_Main_Black
        else -> R.style.AppTheme_Main
    }

    override fun getContainerViewId(): Int = R.id.fragment_container

    override fun getLaunchAnnouncer(): Announcer {
        return if (SecuritySettings.hasPattern()) {
            Announcer(SecurityScene::class.java)
        } else if (!LRRAuthManager.isConfigured()) {
            // LANraragi: show server config if not yet configured
            Announcer(ServerConfigScene::class.java)
        } else {
            val args = Bundle()
            args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
            Announcer(GalleryListScene::class.java).setArgs(args)
        }
    }

    // LANraragi: simplified -- only security gate and server config gate remain
    private fun processAnnouncer(announcer: Announcer): Announcer {
        if (sceneCount == 0) {
            if (SecuritySettings.hasPattern()) {
                val newArgs = Bundle()
                newArgs.putString(SolidScene.KEY_TARGET_SCENE, announcer.clazz.name)
                newArgs.putBundle(SolidScene.KEY_TARGET_ARGS, announcer.args)
                return Announcer(SecurityScene::class.java).setArgs(newArgs)
            } else if (!LRRAuthManager.isConfigured()) {
                val newArgs = Bundle()
                newArgs.putString(SolidScene.KEY_TARGET_SCENE, announcer.clazz.name)
                newArgs.putBundle(SolidScene.KEY_TARGET_ARGS, announcer.args)
                return Announcer(ServerConfigScene::class.java).setArgs(newArgs)
            }
        }
        return announcer
    }

    private fun saveImageToTempFile(file: UniFile?): File? {
        if (file == null) {
            return null
        }

        val bitmap = try {
            BitmapUtils.decodeStream(
                UniFileInputStreamPipe(file),
                -1, -1, 500 * 500, false, false, null
            )
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val temp = AppConfig.createTempFile() ?: return null

        var os: java.io.OutputStream? = null
        return try {
            os = FileOutputStream(temp)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
            temp
        } catch (e: IOException) {
            null
        } finally {
            IOUtils.closeQuietly(os)
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }

        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data ?: return false
            val announcer = LRRUrlOpener.parseUrl(uri.toString())
            if (announcer != null) {
                startScene(processAnnouncer(announcer))
                return true
            }
        } else if (Intent.ACTION_SEND == action) {
            val type = intent.type
            if ("text/plain" == type) {
                val builder = ListUrlBuilder()
                builder.keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                startScene(processAnnouncer(GalleryListScene.getStartAnnouncer(builder)))
                return true
            } else {
                if (type != null && type.startsWith("image/")) {
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        val file = UniFile.fromUri(this, uri)
                        val temp = saveImageToTempFile(file)
                        if (temp != null) {
                            val builder = ListUrlBuilder()
                            builder.mode = ListUrlBuilder.MODE_IMAGE_SEARCH
                            builder.imagePath = temp.path
                            builder.useSimilarityScan = true
                            builder.showExpunged = true
                            startScene(
                                processAnnouncer(GalleryListScene.getStartAnnouncer(builder))
                            )
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    override fun onUnrecognizedIntent(intent: Intent?) {
        val clazz = topSceneClass
        if (clazz != null && SolidScene::class.java.isAssignableFrom(clazz)) {
            // KNOWN-ISSUE (P1): intent is silently dropped when a SolidScene (security/config gate) is showing
            return
        }

        if (!handleIntent(intent)) {
            var handleUrl = false
            if (intent != null && Intent.ACTION_VIEW == intent.action) {
                handleUrl = true
                Toast.makeText(this, R.string.error_cannot_parse_the_url, Toast.LENGTH_SHORT).show()
            }

            if (sceneCount == 0) {
                if (handleUrl) {
                    finish()
                } else {
                    val args = Bundle()
                    args.putString(
                        GalleryListScene.KEY_ACTION,
                        AppearanceSettings.getLaunchPageGalleryListSceneAction()
                    )
                    startScene(
                        processAnnouncer(Announcer(GalleryListScene::class.java).setArgs(args))
                    )
                }
            }
        }
    }

    override fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): Announcer {
        return processAnnouncer(Announcer(clazz).setArgs(args))
    }

    override fun onCreate2(savedInstanceState: Bundle?) {
        var savedState = savedInstanceState
        val intent = intent
        if (intent != null) {
            val res = intent.getBooleanExtra(SplashActivity.KEY_RESTART, false)
            if (res) {
                savedState = null
            }
        }
        setContentView(R.layout.activity_main)

        val drawerLayout = ViewUtils.`$$`(this, R.id.draw_view) as EhDrawerLayout
        mDrawerLayout = drawerLayout
        drawerLayout.setDrawerListener(this)

        // Strip display cutout insets on left/right so fitsSystemWindows doesn't pad
        // for the notch/punch-hole area in landscape. Top/bottom are preserved for
        // status bar (portrait) and navigation bar.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            drawerLayout.setOnApplyWindowInsetsListener { v, insets ->
                val cutout = insets.displayCutout
                if (cutout != null) {
                    val left = Math.max(
                        0,
                        insets.systemWindowInsetLeft - cutout.safeInsetLeft
                    )
                    val top = insets.systemWindowInsetTop // keep top for status bar
                    val right = Math.max(
                        0,
                        insets.systemWindowInsetRight - cutout.safeInsetRight
                    )
                    val bottom = insets.systemWindowInsetBottom // keep bottom for nav bar
                    @Suppress("DEPRECATION")
                    return@setOnApplyWindowInsetsListener v.onApplyWindowInsets(
                        insets.replaceSystemWindowInsets(left, top, right, bottom)
                    )
                }
                v.onApplyWindowInsets(insets)
            }
        }
        val navView = ViewUtils.`$$`(this, R.id.nav_view) as NavigationView
        mNavView = navView
        mRightDrawer = ViewUtils.`$$`(this, R.id.right_drawer) as FrameLayout
        val headerLayout = navView.getHeaderView(0)
        val avatar = ViewUtils.`$$`(headerLayout, R.id.avatar) as AvatarImageView
        mAvatar = avatar
        avatar.setOnClickListener { onAvatarChange() }
        val headerBackground = ViewUtils.`$$`(headerLayout, R.id.header_background) as ImageView
        mHeaderBackground = headerBackground
        headerBackground.setOnClickListener { onBackgroundChange() }
        initUserImage()
        updateProfile()
        mDisplayName = ViewUtils.`$$`(headerLayout, R.id.display_name) as TextView
        val mChangeTheme = ViewUtils.`$$`(this, R.id.change_theme) as TextView

        drawerLayout.setStatusBarColor(
            ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimaryDark)
        )

        navView.setNavigationItemSelectedListener(this)
        if (AppearanceSettings.getTheme() == 0) {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_light))
            mChangeTheme.setBackgroundColor(getColor(R.color.white))
        } else if (AppearanceSettings.getTheme() == 1) {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_other))
            mChangeTheme.setBackgroundColor(getColor(R.color.grey_850))
        } else {
            mChangeTheme.setTextColor(getColor(R.color.theme_change_other))
            mChangeTheme.setBackgroundColor(getColor(R.color.black))
        }

        mChangeTheme.text = getThemeText()
        mChangeTheme.setOnClickListener {
            AppearanceSettings.putTheme(getNextTheme())
            (application as EhApplication).recreate()
        }

        if (savedState == null) {
            onInit()
            checkDownloadLocation()
            if (NetworkSettings.getCellularNetworkWarning()) {
                checkCellularNetwork()
            }
        } else {
            onRestore(savedState)
        }
        EhTagDatabase.update(this)

        // Prompt user to re-enter credentials if KeyStore became unavailable
        if (LRRAuthManager.isNeedsReauthentication()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.lrr_keystore_failed_title)
                .setMessage(R.string.lrr_keystore_failed_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startScene(Announcer(ServerListScene::class.java))
                }
                .setCancelable(false)
                .show()
        }

        // Surface non-KeyStore boot failures (e.g., DB corruption, Room migration
        // error). Sticky one-shot — getAndSet(null) so each failure is shown
        // exactly once. KeyStore-only failures take precedence above.
        AppModule.bootProfileLoadError.getAndSet(null)?.let { err ->
            showBootFailureDialog(err)
        }

        // Cold-start auto update check. No-op if user disabled the toggle, the
        // 1-day throttle hasn't expired, or the latest release is the skipped
        // version. Surfaces newer release via Snackbar with "View" action.
        maybeAutoCheckUpdates()
    }

    /**
     * Three-button recovery dialog for non-KeyStore boot failures (DB
     * corruption, Room migration error, generic loader exception). Without
     * a recovery path the user gets stuck restarting into the same broken
     * state — see backlog Stage 1 / fix-roadmap S2-2.
     *
     * - **Retry**: simple process restart. The original error may have been
     *   transient (e.g. `SQLiteException: disk I/O error` from a flaky FS).
     * - **Reset Database**: opens a second confirm dialog before deleting
     *   `eh.db` and restarting. Destructive and non-undoable.
     * - **Cancel**: dismiss and continue with no profile loaded — same
     *   behaviour as the previous OK-only dialog. Set non-cancelable so the
     *   user must consciously dismiss.
     */
    private fun showBootFailureDialog(err: Throwable) {
        val detail = err.message ?: err.javaClass.simpleName
        AlertDialog.Builder(this)
            .setTitle(R.string.lrr_boot_load_failed_title)
            .setMessage(getString(R.string.lrr_boot_load_failed_message, detail))
            .setPositiveButton(R.string.lrr_boot_load_failed_retry) { _, _ ->
                triggerRebirth()
            }
            .setNegativeButton(R.string.lrr_boot_load_failed_reset_db) { _, _ ->
                showResetDatabaseConfirm()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    /**
     * Second-stage confirm for the destructive "reset database" branch.
     * Cancellable so back / outside-tap aborts; positive button calls
     * [resetDatabaseAndRestart].
     */
    private fun showResetDatabaseConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lrr_boot_load_failed_reset_db_confirm_title)
            .setMessage(R.string.lrr_boot_load_failed_reset_db_confirm_message)
            .setPositiveButton(R.string.lrr_boot_load_failed_reset_db_confirm_yes) { _, _ ->
                resetDatabaseAndRestart()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    /**
     * Delete `eh.db` (plus its `-journal`, `-wal`, `-shm` siblings handled
     * by the framework) on IO, then trigger a process restart so the next
     * `AppDatabase.getInstance` call rebuilds an empty schema.
     */
    private fun resetDatabaseAndRestart() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    applicationContext.deleteDatabase(AppDatabase.DB_NAME)
                } catch (t: Throwable) {
                    Log.w(TAG, "deleteDatabase failed during boot-failure reset", t)
                }
            }
            triggerRebirth()
        }
    }

    /**
     * Restart the whole app process. Schedules a one-shot inexact alarm to
     * relaunch the launcher activity ~100 ms after the current process is
     * killed; standard ProcessPhoenix-style pattern, no extra permission
     * needed (`AlarmManager.set` with `RTC` is inexact and exempt from
     * SCHEDULE_EXACT_ALARM on API 31+). Uses `getLaunchIntentForPackage` so
     * the relaunched task starts at whichever Activity is the registered
     * launcher in the current build.
     */
    private fun triggerRebirth() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.also {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (launchIntent != null) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent)
        }
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    override fun onStart() {
        super.onStart()
        // LANraragi: EhViewer auto-update check disabled
    }

    private fun initUserImage() {
        val headerBackgroundFile = AppearanceSettings.getUserImageFile(AppearanceSettings.USER_BACKGROUND_IMAGE)
        initBackgroundImageData(headerBackgroundFile)
    }

    private fun cleanupBackgroundResources() {
        handlerB.removeCallbacksAndMessages(null)
        gifHandler?.close()
        gifHandler = null
        backgroundBit?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        backgroundBit = null
    }

    private fun initBackgroundImageData(file: File?) {
        // Clean up previous resources
        cleanupBackgroundResources()

        if (file != null) {
            val name = file.name
            val ns = name.split("\\.".toRegex())
            if (ns[1] == "gif" || ns[1] == "GIF") {
                val gif = GifHandler(file.absolutePath)
                gifHandler = gif
                val width = gif.width
                val height = gif.height
                backgroundBit = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val nextFrame = gif.updateFrame(backgroundBit)
                handlerB.sendEmptyMessageDelayed(1, nextFrame.toLong())
            } else {
                backgroundBit = decodeSampledBitmap(file.path)
                mHeaderBackground?.setImageBitmap(backgroundBit)
            }
        }
    }

    override fun backgroundSourceChange(file: File?) {
        if (file == null) {
            cleanupBackgroundResources()
            mHeaderBackground?.setImageResource(R.drawable.sadpanda_low_poly)
        } else {
            initBackgroundImageData(file)
        }
    }

    /**
     * Reload avatar from settings (or reset to default).
     * Called by UserImageChange.resetToDefault().
     */
    fun loadAvatar() {
        val avatar = mAvatar ?: return
        val userAvatarFile = AppearanceSettings.getUserImageFile(AppearanceSettings.USER_AVATAR_IMAGE)
        if (userAvatarFile != null) {
            val bitmap = decodeSampledBitmap(userAvatarFile.path)
            if (bitmap != null) {
                avatar.load(BitmapDrawable(avatar.resources, bitmap))
            } else {
                avatar.load(R.drawable.default_avatar)
            }
        } else {
            avatar.load(R.drawable.default_avatar)
        }
    }

    private fun getThemeText(): String {
        val resId = when (AppearanceSettings.getTheme()) {
            AppearanceSettings.THEME_DARK -> R.string.theme_dark
            AppearanceSettings.THEME_BLACK -> R.string.theme_black
            else -> R.string.theme_light
        }
        return getString(resId)
    }

    private fun getNextTheme(): Int = when (AppearanceSettings.getTheme()) {
        AppearanceSettings.THEME_DARK -> AppearanceSettings.THEME_BLACK
        AppearanceSettings.THEME_BLACK -> AppearanceSettings.THEME_LIGHT
        else -> AppearanceSettings.THEME_DARK
    }

    private fun checkDownloadLocation() {
        val uniFile = DownloadSettings.getDownloadLocation()
        // null == uniFile for first start
        if (uniFile == null || uniFile.ensureDir()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.waring)
            .setMessage(R.string.invalid_download_location)
            .setPositiveButton(R.string.get_it, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun checkCellularNetwork() {
        if (Network.getActiveNetworkType(this) == ConnectivityManager.TYPE_MOBILE) {
            showTip(R.string.cellular_network_warning, BaseScene.LENGTH_SHORT)
        }
    }

    private fun onInit() {
        // EH cookie auth check removed -- login state is managed via LRRAuthManager
    }

    /**
     * Cold-start auto check. Honors the user toggle; all other guards
     * (1-day throttle, skip-version, versionCode comparison) are internal
     * to AppUpdater and surfaced via the sealed UpdateResult.
     *
     * Failure modes (network error, GitHub API non-2xx, unparseable tag) are
     * silent — user discovers via the next day's check or via manual entry.
     */
    private fun maybeAutoCheckUpdates() {
        if (!UpdateSettings.getAutoCheckUpdates()) return

        lifecycleScope.launch {
            val result = AppUpdater.update(manualChecking = false)
            if (result is AppUpdater.UpdateResult.NewerAvailable) {
                showUpdateSnackbar(result.release)
            }
            // UpToDate / NetworkError / Skipped — auto path is silent for all of these.
            // AppUpdater handles throttle, skip-version, and putUpdateTime internally.
        }
    }

    private fun showUpdateSnackbar(release: GhRelease) {
        val host = mDrawerLayout ?: return
        val versionLabel = release.tagName
        Snackbar.make(
            host,
            getString(R.string.update_snackbar_text, versionLabel),
            Snackbar.LENGTH_LONG,
        )
            .setAction(R.string.update_snackbar_action) {
                UpdateDialog(this).showUpdateDialog(release)
            }
            .show()
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mNavCheckedItem = savedInstanceState.getInt(KEY_NAV_CHECKED_ITEM)
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        outState.putInt(KEY_NAV_CHECKED_ITEM, mNavCheckedItem)
    }

    override fun onDestroy() {
        cleanupBackgroundResources()

        super.onDestroy()

        mDrawerLayout = null
        mNavView = null
        mRightDrawer = null
        mAvatar = null
        mDisplayName = null
    }

    override fun onResume() {
        super.onResume()

        setNavCheckedItem(mNavCheckedItem)

        checkClipboardUrl()
    }

    /**
     * MainActivity hosts the SecurityScene, so when the app returns from
     * background and a re-lock is pending, push a fresh SecurityScene on top
     * of the existing scene stack instead of bouncing through the
     * EhActivity default. The scene is launched in re-lock mode so
     * successful unlock just pops it back to the previous scene without
     * resetting navigation.
     */
    override fun onForegroundLockCheck() {
        if (!AppLockGate.consumeShouldRelock()) return
        // Always consume above so a removed pattern doesn't leave the flag set
        // forever. Push SecurityScene only if a pattern is still configured.
        if (!SecuritySettings.hasPattern()) return
        // Skip if SecurityScene is already on top — avoids stacking a
        // duplicate when the user backgrounds the lock prompt itself.
        val top = topSceneClass
        if (top != null && SecurityScene::class.java.isAssignableFrom(top)) return
        val args = Bundle().apply {
            putBoolean(SecurityScene.KEY_RELOCK_MODE, true)
        }
        startScene(Announcer(SecurityScene::class.java).setArgs(args))
    }

    override fun onTransactScene() {
        super.onTransactScene()

        checkClipboardUrl()
    }

    private fun checkClipboardUrl() {
        mainHandler.postDelayed({
            if (!isSolid()) {
                checkClipboardUrlInternal()
            }
        }, 300)
    }

    private fun isSolid(): Boolean {
        val topClass = topSceneClass
        return topClass == null || SolidScene::class.java.isAssignableFrom(topClass)
    }

    private fun getTextFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try {
            if (clipboard != null) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0 && clip.getItemAt(0).text != null) {
                    return clip.getItemAt(0).text.toString()
                }
            }
            null
        } catch (ignore: RuntimeException) {
            null
        }
    }

    private fun checkClipboardUrlInternal() {
        // LANraragi: clipboard URL monitoring disabled (was E-Hentai specific)
    }

    @SuppressLint("RtlHardcoded")
    override fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        super.onSceneViewCreated(scene, savedInstanceState)

        val rightDrawer = mRightDrawer ?: return
        val drawerLayout = mDrawerLayout ?: return
        if (scene is BaseScene) {
            rightDrawer.removeAllViews()
            val drawerView = scene.createDrawerView(
                scene.layoutInflater2, rightDrawer, savedInstanceState
            )
            if (drawerView != null) {
                rightDrawer.addView(drawerView)
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            } else {
                drawerLayout.setDrawerLockMode(
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                    Gravity.RIGHT
                )
            }
        }
    }

    override fun onSceneViewDestroyed(scene: SceneFragment) {
        super.onSceneViewDestroyed(scene)

        if (scene is BaseScene) {
            scene.destroyDrawerView()
        }
    }

    private fun decodeSampledBitmap(path: String, maxDim: Int = MAX_IMAGE_DIMENSION): Bitmap? =
        ImageDecodeUtils.decodeSampledBitmap(path, maxDim)

    fun updateProfile() {
        mAvatar?.let { avatar ->
            val avatarUrl = AppearanceSettings.getAvatar()
            if (TextUtils.isEmpty(avatarUrl)) {
                val userAvatarFile = AppearanceSettings.getUserImageFile(AppearanceSettings.USER_AVATAR_IMAGE)
                if (userAvatarFile != null) {
                    val bitmap = decodeSampledBitmap(userAvatarFile.path)
                    val drawable = BitmapDrawable(avatar.resources, bitmap)
                    avatar.load(drawable)
                } else {
                    avatar.load(R.drawable.default_avatar)
                }
            } else {
                avatar.load(avatarUrl, avatarUrl)
            }
        }

        mDisplayName?.let { displayNameView ->
            var displayName = AppearanceSettings.getDisplayName()
            if (TextUtils.isEmpty(displayName)) {
                displayName = getString(R.string.default_display_name)
            }
            Toast.makeText(this, displayName, Toast.LENGTH_LONG).show()
            displayNameView.text = displayName
        }
    }

    fun addAboveSnackView(view: View) {
        mDrawerLayout?.addAboveSnackView(view)
    }

    fun removeAboveSnackView(view: View) {
        mDrawerLayout?.removeAboveSnackView(view)
    }

    /**
     * 更换壁纸
     */
    fun onBackgroundChange() {
        val change = UserImageChange(
            this@MainActivity,
            UserImageChange.CHANGE_BACKGROUND,
            layoutInflater,
            LayoutInflater.from(this@MainActivity),
            this,
            cameraLauncher,
            albumLauncher,
            cropLauncher,
        )
        userImageChange = change
        change.showImageChangeDialog()
    }

    /**
     * 更换头像
     */
    fun onAvatarChange() {
        val change = UserImageChange(
            this@MainActivity,
            UserImageChange.CHANGE_AVATAR,
            layoutInflater,
            LayoutInflater.from(this@MainActivity),
            this,
            cameraLauncher,
            albumLauncher,
            cropLauncher,
        )
        userImageChange = change
        change.showImageChangeDialog()
    }

    fun setDrawerLockMode(lockMode: Int, edgeGravity: Int) {
        mDrawerLayout?.setDrawerLockMode(lockMode, edgeGravity)
    }

    fun openDrawer(drawerGravity: Int) {
        mDrawerLayout?.openDrawer(drawerGravity)
    }

    fun closeDrawer(drawerGravity: Int) {
        mDrawerLayout?.closeDrawer(drawerGravity)
    }

    fun toggleDrawer(drawerGravity: Int) {
        val drawer = mDrawerLayout ?: return
        if (drawer.isDrawerOpen(drawerGravity)) {
            drawer.closeDrawer(drawerGravity)
        } else {
            drawer.openDrawer(drawerGravity)
        }
    }

    fun setDrawerGestureBlocker(gestureBlocker: DrawerLayout.GestureBlocker?) {
        mDrawerLayout?.setGestureBlocker(gestureBlocker)
    }

    val isDrawersVisible: Boolean
        get() = mDrawerLayout?.isDrawersVisible ?: false

    fun setNavCheckedItem(@IdRes resId: Int) {
        mNavCheckedItem = resId
        mNavView?.let { navView ->
            if (resId == 0) {
                navView.setCheckedItem(R.id.nav_stub)
            } else {
                navView.setCheckedItem(resId)
            }
        }
    }

    fun showTip(@StringRes id: Int, length: Int) {
        showTip(getString(id), length)
    }

    /**
     * If activity is running, show snack bar, otherwise show toast
     */
    fun showTip(message: CharSequence, length: Int) {
        val drawer = mDrawerLayout
        if (drawer != null) {
            Snackbar.make(
                drawer, message,
                if (length == BaseScene.LENGTH_LONG) 5000 else 3000
            ).show()
        } else {
            Toast.makeText(
                this, message,
                if (length == BaseScene.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("RtlHardcoded")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val drawer = mDrawerLayout
        if (drawer != null && (drawer.isDrawerOpen(Gravity.LEFT) ||
                drawer.isDrawerOpen(Gravity.RIGHT))
        ) {
            drawer.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("NonConstantResourceId", "RtlHardcoded")
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Don't select twice
        if (item.isChecked) {
            return false
        }

        val id = item.itemId

        when (item.itemId) {
            R.id.nav_homepage -> {
                val navHomepage = Bundle()
                navHomepage.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
                startSceneFirstly(
                    Announcer(GalleryListScene::class.java).setArgs(navHomepage)
                )
            }
            R.id.nav_favourite -> startScene(Announcer(LRRCategoriesScene::class.java))
            R.id.nav_history -> startScene(Announcer(HistoryScene::class.java))
            R.id.nav_downloads -> startScene(Announcer(DownloadsScene::class.java))
            R.id.nav_settings -> {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            }
            R.id.nav_server_config -> {
                // Show server list if profiles exist, otherwise direct to config
                lifecycleScope.launch {
                    try {
                        val profileCount = withContext(Dispatchers.IO) {
                            ServiceRegistry.dataModule.profileRepository.getAllProfiles().size
                        }
                        if (profileCount > 0) {
                            startScene(Announcer(ServerListScene::class.java))
                        } else {
                            startScene(Announcer(ServerConfigScene::class.java))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load server profiles", e)
                    }
                }
            }
        }

        if (id != R.id.nav_stub) {
            mDrawerLayout?.closeDrawers()
        }

        return true
    }

    override fun onDrawerSlide(drawerView: View, percent: Float) {
    }

    override fun onDrawerOpened(drawerView: View) {
    }

    override fun onDrawerClosed(drawerView: View) {
    }

    override fun onDrawerStateChanged(drawerView: View, newState: Int) {
    }
}
