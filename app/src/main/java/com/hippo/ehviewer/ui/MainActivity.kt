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
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.settings.NetworkSettings
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.callBack.ImageChangeCallBack
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.ui.main.UserImageChange
import com.hippo.ehviewer.ui.scene.AnalyticsScene
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.ServerConfigScene
import com.hippo.ehviewer.ui.scene.ServerListScene
import com.hippo.ehviewer.ui.scene.download.DownloadLabelsScene
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.ui.scene.gallery.list.FavoritesScene
import com.hippo.ehviewer.ui.scene.LRRCategoriesScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene
import com.hippo.ehviewer.ui.scene.gallery.list.SubscriptionsScene
import com.hippo.ehviewer.ui.scene.history.HistoryScene
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene
import com.hippo.ehviewer.ui.scene.SecurityScene
import com.hippo.ehviewer.ui.scene.SolidScene
import com.hippo.ehviewer.ui.splash.SplashActivity
import com.hippo.ehviewer.client.EhUrlOpener
import com.hippo.ehviewer.widget.EhDrawerLayout
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.network.Network
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.scene.StageActivity
import com.hippo.unifile.UniFile
import com.hippo.util.BitmapUtils
import com.hippo.util.GifHandler
import androidx.lifecycle.lifecycleScope
import com.hippo.util.IoThreadPoolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.widget.AvatarImageView
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.ResourcesUtils
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.lib.yorozuya.ViewUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : StageActivity(),
    NavigationView.OnNavigationItemSelectedListener, ImageChangeCallBack, DrawerLayout.DrawerListener {

    companion object {
        private const val PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE = 0
        private const val REQUEST_CODE_SETTINGS = 0
        private const val KEY_NAV_CHECKED_ITEM = "nav_checked_item"

        init {
            registerLaunchMode(SecurityScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)

            registerLaunchMode(AnalyticsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(ServerConfigScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(ServerListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(GalleryListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(QuickSearchScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(SubscriptionsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(GalleryDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)

            registerLaunchMode(GalleryPreviewsScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(DownloadsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(DownloadLabelsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(FavoritesScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(LRRCategoriesScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(HistoryScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(ProgressScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
        }
    }

    /*---------------
     Whole life cycle
     ---------------*/
    private var mDrawerLayout: EhDrawerLayout? = null
    private var mNavView: NavigationView? = null
    private var mRightDrawer: FrameLayout? = null
    private var mAvatar: AvatarImageView? = null
    private var mHeaderBackground: ImageView? = null
    private var mDisplayName: TextView? = null
    private var userImageChange: UserImageChange? = null

    private var mNavCheckedItem = 0

    @JvmField
    var gifHandler: GifHandler? = null

    @JvmField
    var backgroundBit: Bitmap? = null

    private val handlerB: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (gifHandler == null || backgroundBit == null || backgroundBit!!.isRecycled) return
            val mNextFrame = gifHandler!!.updateFrame(backgroundBit)
            if (mNextFrame > 0) {
                sendEmptyMessageDelayed(1, mNextFrame.toLong())
            }
            if (mHeaderBackground != null) {
                mHeaderBackground!!.setImageBitmap(backgroundBit)
            }
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
            val announcer = EhUrlOpener.parseUrl(uri.toString())
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

        mDrawerLayout = ViewUtils.`$$`(this, R.id.draw_view) as EhDrawerLayout
        mDrawerLayout!!.setDrawerListener(this)

        // Strip display cutout insets on left/right so fitsSystemWindows doesn't pad
        // for the notch/punch-hole area in landscape. Top/bottom are preserved for
        // status bar (portrait) and navigation bar.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            mDrawerLayout!!.setOnApplyWindowInsetsListener { v, insets ->
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
        mNavView = ViewUtils.`$$`(this, R.id.nav_view) as NavigationView
        mRightDrawer = ViewUtils.`$$`(this, R.id.right_drawer) as FrameLayout
        val headerLayout = mNavView!!.getHeaderView(0)
        mAvatar = ViewUtils.`$$`(headerLayout, R.id.avatar) as AvatarImageView
        mAvatar!!.setOnClickListener { onAvatarChange() }
        mHeaderBackground = ViewUtils.`$$`(headerLayout, R.id.header_background) as ImageView
        mHeaderBackground!!.setOnClickListener { onBackgroundChange() }
        initUserImage()
        updateProfile()
        mDisplayName = ViewUtils.`$$`(headerLayout, R.id.display_name) as TextView
        val mChangeTheme = ViewUtils.`$$`(this, R.id.change_theme) as TextView

        mDrawerLayout!!.setStatusBarColor(
            ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimaryDark)
        )

        if (mNavView != null) {
            mNavView!!.setNavigationItemSelectedListener(this)
        }
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
    }

    override fun onStart() {
        super.onStart()
        // LANraragi: EhViewer auto-update check disabled
    }

    private fun initUserImage() {
        val headerBackgroundFile = Settings.getUserImageFile(Settings.USER_BACKGROUND_IMAGE)
        initBackgroundImageData(headerBackgroundFile)
    }

    private fun cleanupBackgroundResources() {
        handlerB.removeCallbacksAndMessages(null)
        if (gifHandler != null) {
            gifHandler!!.close()
            gifHandler = null
        }
        if (backgroundBit != null && !backgroundBit!!.isRecycled) {
            backgroundBit!!.recycle()
            backgroundBit = null
        }
    }

    private fun initBackgroundImageData(file: File?) {
        // Clean up previous resources
        cleanupBackgroundResources()

        if (file != null) {
            val name = file.name
            val ns = name.split("\\.".toRegex())
            if (ns[1] == "gif" || ns[1] == "GIF") {
                gifHandler = GifHandler(file.absolutePath)
                val width = gifHandler!!.width
                val height = gifHandler!!.height
                backgroundBit = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val nextFrame = gifHandler!!.updateFrame(backgroundBit)
                handlerB.sendEmptyMessageDelayed(1, nextFrame.toLong())
            } else {
                backgroundBit = BitmapFactory.decodeFile(file.path)
                mHeaderBackground!!.setImageBitmap(backgroundBit)
            }
        }
    }

    override fun backgroundSourceChange(file: File?) {
        if (file == null) {
            cleanupBackgroundResources()
            if (mHeaderBackground != null) {
                mHeaderBackground!!.setImageResource(R.drawable.sadpanda_low_poly)
            }
        } else {
            initBackgroundImageData(file)
        }
    }

    /**
     * Reload avatar from settings (or reset to default).
     * Called by UserImageChange.resetToDefault().
     */
    fun loadAvatar() {
        if (mAvatar == null) return
        val userAvatarFile = Settings.getUserImageFile(Settings.USER_AVATAR_IMAGE)
        if (userAvatarFile != null) {
            val bitmap = BitmapFactory.decodeFile(userAvatarFile.path)
            if (bitmap != null) {
                mAvatar!!.load(BitmapDrawable(mAvatar!!.resources, bitmap))
            } else {
                mAvatar!!.load(R.drawable.default_avatar)
            }
        } else {
            mAvatar!!.load(R.drawable.default_avatar)
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

    override fun onTransactScene() {
        super.onTransactScene()

        checkClipboardUrl()
    }

    private fun checkClipboardUrl() {
        SimpleHandler.getInstance().postDelayed({
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.size == 1 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.you_rejected_me, Toast.LENGTH_SHORT).show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        super.onSceneViewCreated(scene, savedInstanceState)

        if (scene is BaseScene && mRightDrawer != null && mDrawerLayout != null) {
            mRightDrawer!!.removeAllViews()
            val drawerView = scene.createDrawerView(
                scene.layoutInflater2, mRightDrawer, savedInstanceState
            )
            if (drawerView != null) {
                mRightDrawer!!.addView(drawerView)
                mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            } else {
                mDrawerLayout!!.setDrawerLockMode(
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

    fun updateProfile() {
        if (mAvatar != null) {
            val avatarUrl = Settings.getAvatar()
            if (TextUtils.isEmpty(avatarUrl)) {
                val userAvatarFile = Settings.getUserImageFile(Settings.USER_AVATAR_IMAGE)
                if (userAvatarFile != null) {
                    val bitmap = BitmapFactory.decodeFile(userAvatarFile.path)
                    val drawable = BitmapDrawable(mAvatar!!.resources, bitmap)
                    mAvatar!!.load(drawable)
                } else {
                    mAvatar!!.load(R.drawable.default_avatar)
                }
            } else {
                mAvatar!!.load(avatarUrl, avatarUrl)
            }
        }

        if (mDisplayName != null) {
            var displayName = Settings.getDisplayName()
            if (TextUtils.isEmpty(displayName)) {
                displayName = getString(R.string.default_display_name)
            }
            Toast.makeText(this, displayName, Toast.LENGTH_LONG).show()
            mDisplayName!!.text = displayName
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
        userImageChange = null
        userImageChange = UserImageChange(
            this@MainActivity,
            UserImageChange.CHANGE_BACKGROUND,
            layoutInflater,
            LayoutInflater.from(this@MainActivity),
            this
        )
        userImageChange!!.showImageChangeDialog()
    }

    /**
     * 更换头像
     */
    fun onAvatarChange() {
        userImageChange = null
        userImageChange = UserImageChange(
            this@MainActivity,
            UserImageChange.CHANGE_AVATAR,
            layoutInflater,
            LayoutInflater.from(this@MainActivity),
            this
        )
        userImageChange!!.showImageChangeDialog()
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
        if (mDrawerLayout != null) {
            if (mDrawerLayout!!.isDrawerOpen(drawerGravity)) {
                mDrawerLayout!!.closeDrawer(drawerGravity)
            } else {
                mDrawerLayout!!.openDrawer(drawerGravity)
            }
        }
    }

    fun setDrawerGestureBlocker(gestureBlocker: DrawerLayout.GestureBlocker?) {
        mDrawerLayout?.setGestureBlocker(gestureBlocker)
    }

    val isDrawersVisible: Boolean
        get() = mDrawerLayout?.isDrawersVisible ?: false

    fun setNavCheckedItem(@IdRes resId: Int) {
        mNavCheckedItem = resId
        if (mNavView != null) {
            if (resId == 0) {
                mNavView!!.setCheckedItem(R.id.nav_stub)
            } else {
                mNavView!!.setCheckedItem(resId)
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
        if (mDrawerLayout != null) {
            Snackbar.make(
                mDrawerLayout!!, message,
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
        if (mDrawerLayout != null && (mDrawerLayout!!.isDrawerOpen(Gravity.LEFT) ||
                mDrawerLayout!!.isDrawerOpen(Gravity.RIGHT))
        ) {
            mDrawerLayout!!.closeDrawers()
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
                val intent = Intent(this, SettingsActivity::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_SETTINGS)
            }
            R.id.nav_server_config -> {
                // Show server list if profiles exist, otherwise direct to config
                lifecycleScope.launch {
                    val profileCount = withContext(Dispatchers.IO) {
                        EhDB.getAllServerProfilesAsync().size
                    }
                    if (profileCount > 0) {
                        startScene(Announcer(ServerListScene::class.java))
                    } else {
                        startScene(Announcer(ServerConfigScene::class.java))
                    }
                }
            }
        }

        if (id != R.id.nav_stub && mDrawerLayout != null) {
            mDrawerLayout!!.closeDrawers()
        }

        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SETTINGS) {
            if (RESULT_OK == resultCode) {
                refreshTopScene()
            }
            return
        }
        if ((requestCode == UserImageChange.TAKE_CAMERA ||
                    requestCode == UserImageChange.PICK_PHOTO ||
                    requestCode == UserImageChange.CROP_PHOTO) && userImageChange != null
        ) {
            userImageChange!!.saveImageForResult(requestCode, resultCode, data, mAvatar)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
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
