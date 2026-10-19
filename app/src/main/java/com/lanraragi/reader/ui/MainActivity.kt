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

import android.annotation.SuppressLint
import android.util.Log
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.hippo.drawerlayout.DrawerLayout
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.settings.NetworkSettings
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.client.TagTranslationDatabase
import com.lanraragi.reader.dao.DatabaseResetNotice
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.CredentialResetDialog
import com.lanraragi.reader.ui.scene.LegacyLockRemovedNotice
import com.lanraragi.reader.ui.scene.ServerConfigScene
import com.lanraragi.reader.ui.scene.ReauthPrompt
import com.lanraragi.reader.ui.scene.ServerListScene
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.ui.scene.SecurityScene
import com.lanraragi.reader.widget.AppDrawerLayout
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.framework.scene.SceneFactory
import com.lanraragi.framework.scene.SceneFragment
import com.lanraragi.framework.scene.StageActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lanraragi.framework.lib.yorozuya.ResourcesUtils
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import java.io.File

class MainActivity : StageActivity(),
    NavigationView.OnNavigationItemSelectedListener, DrawerLayout.DrawerListener {

    companion object {
        /**
         * Non-exported alias of MainActivity (see the manifest). In-app
         * start_scene intents (notifications, URL opener) target it, so
         * MainActivity can tell them from intents sent by other apps.
         */
        const val INTERNAL_SCENE_ENTRY = "com.lanraragi.reader.ui.InternalSceneEntry"

        /**
         * Scenes another app may open through the exported MainActivity:
         * only what notifications posted by older versions still target.
         */
        private val EXTERNAL_SCENE_ALLOWLIST = setOf(DownloadsScene::class.java.name)

        @VisibleForTesting
        internal fun isSceneIntentAccepted(
            componentClass: String?,
            clazzName: String,
            isRegistered: (String) -> Boolean,
        ): Boolean {
            if (!isRegistered(clazzName)) return false
            return componentClass == INTERNAL_SCENE_ENTRY || clazzName in EXTERNAL_SCENE_ALLOWLIST
        }

        /** An intent for [INTERNAL_SCENE_ENTRY]. */
        @JvmStatic
        fun internalSceneIntent(context: Context): Intent =
            Intent().setClassName(context.packageName, INTERNAL_SCENE_ENTRY)

        private const val TAG = "MainActivity"
        private const val KEY_NAV_CHECKED_ITEM = "nav_checked_item"

        /**
         * Set on the relaunch intent after [LRReaderApplication.restart] so the new
         * process discards the saved scene stack of the killed one.
         */
        const val KEY_RESTART: String = "restart"

        init {
            MainScenes.register()
        }
    }

    /*---------------
     Whole life cycle
     ---------------*/
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mDrawerLayout: AppDrawerLayout? = null
    private var mNavView: NavigationView? = null
    private var mRightDrawer: FrameLayout? = null

    private val mRouter = MainIntentRouter(this)

    private val mNotices = MainActivityNotices(this, host = { mDrawerLayout })

    private val mDatabaseReset = DatabaseResetFlow(this, restart = { triggerRebirth() })

    private val mNavDrawer = MainNavDrawer(
        this,
        openSettings = { settingsLauncher.launch(Intent(this, SettingsActivity::class.java)) },
        closeDrawers = { mDrawerLayout?.closeDrawers() },
    )

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshTopScene()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored: if denied, download notifications simply stay hidden */ }

    private var mNavCheckedItem = 0

    override fun getThemeResId(theme: Int): Int = when (theme) {
        AppearanceSettings.THEME_DARK -> R.style.AppTheme_Main_Dark
        AppearanceSettings.THEME_BLACK -> R.style.AppTheme_Main_Black
        else -> R.style.AppTheme_Main
    }

    override fun getContainerViewId(): Int = R.id.fragment_container

    override fun getLaunchAnnouncer(): Announcer {
        return if (SecuritySettings.isLockEnabled()) {
            Announcer(SecurityScene::class.java)
        } else if (!LRRAuthManager.isConfiguredFast()) {
            // LANraragi: show server config if not yet configured
            val announcer = Announcer(ServerConfigScene::class.java)
            // Benchmark builds only (PerfSeam): auto-fill and submit the form.
            PerfSeam.serverUrlFrom(intent)?.let { url ->
                announcer.setArgs(Bundle().apply { putString(ServerConfigScene.KEY_PERF_SERVER_URL, url) })
            }
            announcer
        } else {
            val args = Bundle()
            args.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
            Announcer(GalleryListScene::class.java).setArgs(args)
        }
    }

    override fun onUnrecognizedIntent(intent: Intent?) = mRouter.onUnrecognizedIntent(intent)

    override fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): Announcer? =
        mRouter.onStartSceneFromIntent(clazz, args)

    /**
     * Request POST_NOTIFICATIONS once on Android 13+. Without the grant, download
     * progress / completion / failure / "waiting for network" notifications are all
     * silently dropped. The system shows the dialog at most twice then auto-denies, so
     * launching unconditionally when not granted is safe (no nagging on later launches).
     */
    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate2(savedInstanceState: Bundle?) {
        // A relaunch after LRReaderApplication.restart() must not restore the dead
        // process's scene stack.
        val savedState = if (intent?.getBooleanExtra(KEY_RESTART, false) == true) {
            null
        } else {
            savedInstanceState
        }
        setContentView(R.layout.activity_main)

        maybeRequestNotificationPermission()

        val drawerLayout = ViewUtils.`$$`(this, R.id.draw_view) as AppDrawerLayout
        mDrawerLayout = drawerLayout
        drawerLayout.setDrawerListener(this)

        mNavDrawer.applyCutoutInsets(drawerLayout)
        val navView = ViewUtils.`$$`(this, R.id.nav_view) as NavigationView
        mNavView = navView
        mRightDrawer = ViewUtils.`$$`(this, R.id.right_drawer) as FrameLayout
        val themeSwitch = ViewUtils.`$$`(this, R.id.theme_switch)

        drawerLayout.setStatusBarColor(
            ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimaryDark)
        )

        navView.setNavigationItemSelectedListener(this)
        mNotices.observeTankTagSyncFailures()
        mNavDrawer.bindHeader(navView)

        mNavDrawer.bindThemeSwitch(themeSwitch)

        if (savedState == null) {
            onInit()
            mNotices.checkDownloadLocation()
            if (NetworkSettings.getCellularNetworkWarning()) {
                mNotices.checkCellularNetwork()
            }
        } else {
            onRestore(savedState)
            // A scene stack restored after process death must not show
            // before the lock: push the lock screen over it right away.
            onForegroundLockCheck()
        }
        TagTranslationDatabase.update(this)

        // Prompt user to re-enter credentials: the KeyStore became unavailable,
        // or it works but saved API keys are missing (after a credential reset
        // or a restore on a new device).
        if (LRRAuthManager.isNeedsReauthentication()) {
            val storeAvailable = LRRAuthManager.isSecureStorageAvailable()
            val prompt = ReauthPrompt.startup(storeAvailable)
            val openServerList: () -> Unit = {
                val args = Bundle().apply { putBoolean(ReauthPrompt.ARG_KEYS_MISSING_PROMPTED, storeAvailable) }
                startScene(Announcer(ServerListScene::class.java).setArgs(args))
            }
            val builder = AlertDialog.Builder(this)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> openServerList() }
                .setCancelable(false)
            // A store that can never be read again (e.g. copied from another
            // phone) needs a way out for users without an app lock (REL-01).
            CredentialResetDialog.offerResetIfStuck(builder, this, onCancel = openServerList)
                .show()
        }

        // An old SHA-256 app lock was dropped on upgrade: say so once (SEC-08).
        LegacyLockRemovedNotice.showIfPending(this)

        // Surface non-KeyStore boot failures (e.g., DB corruption, Room migration
        // error). The loader usually fails after this point, so the presenter
        // observes the slot while STARTED and waits out the lock screen
        // (STAB-02). KeyStore-only failures take precedence above.
        bootNotices = BootNoticePresenter(
            activity = this,
            isLockScreenUp = ::isLockScreenUp,
            onRetry = ::triggerRebirth,
            onResetDatabase = mDatabaseReset::showConfirm,
            onResetNoticeSeen = { stamp -> DatabaseResetNotice.markSeen(Settings.getPreferences(), stamp) },
        ).also { it.install() }

        // Cold-start auto update check. No-op if user disabled the toggle, the
        // 1-day throttle hasn't expired, or the latest release is the skipped
        // version. Surfaces newer release via Snackbar with "View" action.
        mNotices.maybeAutoCheckUpdates()

        purgeLegacyHeaderCustomization()

        // After a language-switch process restart, take the user back to the
        // screen the switch was made on (Advanced settings) instead of
        // stranding them on the home scene. One-shot flag written just before
        // the restart, so ordinary launches never enter this branch.
        // NO_ANIMATION: the user is mid-"seamless" language switch — the home
        // scene is only a technical stopover, so the settings screen should
        // snap into place rather than slide in over it.
        if (AppearanceSettings.consumeLanguageRestartRoute()) {
            settingsLauncher.launch(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(
                        SettingsActivity.KEY_INITIAL_SCREEN,
                        SettingsActivity.SCREEN_ADVANCED
                    )
            )
        }
    }

    /**
     * One-time purge of data persisted by the retired avatar/background/
     * display-name customization (removed 2026-07-24). Older installs may
     * still carry the image files in filesDir plus their SharedPreferences
     * keys; the pref-read guard keeps this a no-op after the first run.
     */
    private fun purgeLegacyHeaderCustomization() {
        val legacyPathKeys = listOf("background_image_path", "avatar_image_path")
        val legacyValueKeys = legacyPathKeys + listOf("display_name", "avatar")
        val stalePaths = legacyPathKeys.mapNotNull { Settings.getString(it, null) }
        val hasStaleValues = stalePaths.isNotEmpty() ||
            legacyValueKeys.any { Settings.getString(it, null) != null }
        if (!hasStaleValues) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Persistent copies used fixed names in filesDir; camera/album
                // flows stored the pref path directly (externalCacheDir).
                (
                    stalePaths.map { File(it) } +
                        listOf(File(filesDir, "avatar_image.jpg"), File(filesDir, "background_image.jpg"))
                    ).forEach { it.delete() }
            } catch (e: SecurityException) {
                Log.w(TAG, "legacy header image cleanup failed", e)
            }
            legacyValueKeys.forEach { Settings.putString(it, null) }
        }
    }

    /** Shows boot notices ([BootNoticePresenter]); set in onCreate2. */
    private var bootNotices: BootNoticePresenter? = null

    /**
     * The app lock has not been passed yet, or its screen is on top: boot
     * notices wait so nothing about the library shows before unlock.
     */
    private fun isLockScreenUp(): Boolean {
        if (AppLockGate.isLocked()) return true
        val top = topSceneClass
        return top != null && SecurityScene::class.java.isAssignableFrom(top)
    }

    /**
     * Restart the whole app process, finishing this task's activities first so the
     * relaunch starts from a clean stack. The process-rebirth mechanics live in
     * [LRReaderApplication.restart].
     */
    private fun triggerRebirth() {
        finishAffinity()
        (application as LRReaderApplication).restart()
    }

    override fun onStart() {
        super.onStart()
        // LANraragi: EhViewer auto-update check disabled
        mNotices.onStart()
    }

    override fun onStop() {
        mNotices.onStop()
        super.onStop()
    }

    private fun onInit() {
        // EH cookie auth check removed -- login state is managed via LRRAuthManager
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mNavCheckedItem = savedInstanceState.getInt(KEY_NAV_CHECKED_ITEM)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Use the single-arg overload: the two-arg PersistableBundle variant only fires
        // for persistableMode activities (which MainActivity is not), so the nav item was
        // never actually saved.
        outState.putInt(KEY_NAV_CHECKED_ITEM, mNavCheckedItem)
    }

    override fun onDestroy() {
        super.onDestroy()

        mDrawerLayout = null
        mNavView = null
        mRightDrawer = null
    }

    override fun onResume() {
        super.onResume()

        setNavCheckedItem(mNavCheckedItem)
    }

    /**
     * MainActivity hosts the SecurityScene, so when the app returns from
     * background and a re-lock is pending, push a fresh SecurityScene on top
     * of the existing scene stack instead of bouncing through the
     * BaseActivity default. The scene is launched in re-lock mode so
     * successful unlock just pops it back to the previous scene without
     * resetting navigation.
     */
    override fun hostsLockScreen(): Boolean = true

    /**
     * start_scene is only honoured for registered scenes, and — when it did
     * not arrive through the non-exported [INTERNAL_SCENE_ENTRY] alias, i.e.
     * possibly from another app — only for [EXTERNAL_SCENE_ALLOWLIST].
     */
    override fun acceptSceneIntent(intent: Intent, clazzName: String): Boolean =
        isSceneIntentAccepted(intent.component?.className, clazzName, SceneFactory::isRegistered)

    override fun onForegroundLockCheck() {
        if (!AppLockGate.isLocked()) return
        // Skip if SecurityScene is already on top (the cold-start launch
        // scene, or the user backgrounded the lock prompt itself), and while
        // no scene exists yet (the launch announcer is the lock screen).
        val top = topSceneClass ?: return
        if (SecurityScene::class.java.isAssignableFrom(top)) return
        val args = Bundle().apply {
            putBoolean(SecurityScene.KEY_RELOCK_MODE, true)
        }
        startScene(Announcer(SecurityScene::class.java).setArgs(args))
    }

    override fun onTransactScene() {
        super.onTransactScene()

        // Posted, not inline: StageActivity.finishScene fires onTransactScene
        // BEFORE removing the popped scene's tag, so during the gate's own
        // dismissal topSceneClass still reads as the gate. One handler hop
        // later the stack is settled.
        mainHandler.post {
            mRouter.maybeReplayPendingGateRequest()
            // The lock screen may just have gone: show what waited for it.
            bootNotices?.maybeShow()
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        super.onSceneViewCreated(scene, savedInstanceState)
        // Breadcrumb for crash reports and the diagnostics bundle (audit C06).
        com.lanraragi.reader.diagnostics.DiagLog.i("Scene", scene.javaClass.simpleName)

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

    override fun onPredictiveBackCancelled(top: SceneFragment) {
        super.onPredictiveBackCancelled(top)
        if (top is BaseScene) top.applySceneChrome()
    }

    override fun onSceneViewDestroyed(scene: SceneFragment) {
        super.onSceneViewDestroyed(scene)

        if (scene is BaseScene) {
            scene.destroyDrawerView()
        }
    }

    fun addAboveSnackView(view: View) {
        mDrawerLayout?.addAboveSnackView(view)
    }

    fun removeAboveSnackView(view: View) {
        mDrawerLayout?.removeAboveSnackView(view)
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

    /** [showTip] with one action; degrades to a plain toast (no action) without a drawer. */
    fun showTip(message: CharSequence, length: Int, actionText: CharSequence, action: () -> Unit) {
        val drawer = mDrawerLayout
        if (drawer != null) {
            Snackbar.make(
                drawer, message,
                if (length == BaseScene.LENGTH_LONG) 5000 else 3000
            ).setAction(actionText) { action() }.show()
        } else {
            showTip(message, length)
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun hasInterceptBack(): Boolean {
        val drawer = mDrawerLayout ?: return false
        return drawer.isDrawerOpen(Gravity.LEFT) || drawer.isDrawerOpen(Gravity.RIGHT)
    }

    override fun onInterceptBack(): Boolean {
        val open = hasInterceptBack()
        if (open) mDrawerLayout?.closeDrawers()
        return open
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = mNavDrawer.onItemSelected(item)

    override fun onDrawerSlide(drawerView: View, percent: Float) {
    }

    override fun onDrawerOpened(drawerView: View) {
        updateBackCallback()
    }

    override fun onDrawerClosed(drawerView: View) {
        updateBackCallback()
    }

    override fun onDrawerStateChanged(drawerView: View, newState: Int) {
    }
}
