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
import android.util.Log
import com.hippo.ehviewer.appwidget.ContinueReadingWidget
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.AppLockGate
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.settings.NetworkSettings
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.download.DownloadResumeBanner
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.module.AppModule
import com.lanraragi.reader.client.api.LRRAuthManager
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
import com.hippo.ehviewer.ui.scene.TankoubonDetailScene
import com.hippo.ehviewer.ui.scene.TankoubonsScene
import com.hippo.ehviewer.ui.splash.SplashActivity
import com.hippo.ehviewer.client.LRRUrlOpener
import com.hippo.ehviewer.widget.EhDrawerLayout
import com.hippo.network.Network
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFactory
import com.hippo.scene.SceneFragment
import com.hippo.scene.StageActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hippo.ehviewer.dao.ServerProfile
import com.lanraragi.reader.client.api.parseBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.lib.yorozuya.ResourcesUtils
import com.hippo.lib.yorozuya.ViewUtils
import java.io.File
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.updater.GhRelease
import com.hippo.ehviewer.ui.dialog.UpdateDialog

class MainActivity : StageActivity(),
    NavigationView.OnNavigationItemSelectedListener, DrawerLayout.DrawerListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_NAV_CHECKED_ITEM = "nav_checked_item"

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
            registerLaunchMode(TankoubonsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(TankoubonDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(HistoryScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(
                com.hippo.ehviewer.ui.scene.stats.ReadingStatsScene::class.java,
                SceneFragment.LAUNCH_MODE_SINGLE_TASK
            )

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
            SceneFactory.register(TankoubonsScene::class.java.name) { TankoubonsScene() }
            SceneFactory.register(TankoubonDetailScene::class.java.name) { TankoubonDetailScene() }
            SceneFactory.register(HistoryScene::class.java.name) { HistoryScene() }
            SceneFactory.register(
                com.hippo.ehviewer.ui.scene.stats.ReadingStatsScene::class.java.name
            ) { com.hippo.ehviewer.ui.scene.stats.ReadingStatsScene() }
        }
    }

    /*---------------
     Whole life cycle
     ---------------*/
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mDrawerLayout: EhDrawerLayout? = null
    private var mNavView: NavigationView? = null
    private var mRightDrawer: FrameLayout? = null

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

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }

        val action = intent.action
        if (ContinueReadingShortcut.ACTION_CONTINUE_READING == action) {
            val arcid = intent.getStringExtra(ContinueReadingShortcut.KEY_ARCID)
            val profileId = intent.getLongExtra(ContinueReadingShortcut.KEY_PROFILE_ID, -1L)
            if (!arcid.isNullOrEmpty() && profileId > 0) {
                openContinueReading(arcid, profileId)
            }
            // Deliberately fall through as "unhandled": the default scene
            // stack builds underneath and the reader lands on top, so BACK
            // from the reader returns to the main list.
            return false
        }
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data ?: return false
            val announcer = LRRUrlOpener.parseUrl(uri.toString())
            if (announcer != null) {
                startScene(processAnnouncer(announcer))
                return true
            }
        } else if (Intent.ACTION_SEND == action) {
            // Shared images used to launch the EhViewer image search; LANraragi
            // has no image-search API, so only shared text becomes a keyword search.
            if ("text/plain" == intent.type) {
                val builder = ListUrlBuilder()
                builder.keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                startScene(processAnnouncer(GalleryListScene.getStartAnnouncer(builder)))
                return true
            }
        }

        return false
    }

    /**
     * Continue-reading deep link (issue #15): rebuild the Archive from its
     * history snapshot and route through the canonical read-intent path
     * (position restore + offline handling ride along for free). No snapshot
     * means the row is gone — stay on the main list.
     */
    private fun openContinueReading(arcid: String, profileId: Long) {
        lifecycleScope.launch {
            val archive = try {
                withContext(Dispatchers.IO) {
                    // Profile gone (deleted since publish) or history row gone:
                    // both mean the shortcut is stale — self-heal by removing
                    // it and stay on the main list with a toast (issue #16).
                    if (ServiceRegistry.dataModule.profileRepository.findById(profileId) == null) {
                        null
                    } else {
                        ServiceRegistry.dataModule.historyRepository
                            .getArchiveSnapshot(arcid, profileId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "continue-reading snapshot load failed", e)
                null
            }
            if (archive == null) {
                ContinueReadingShortcut.remove(this@MainActivity)
                // The widget may point at the same stale target (it shares the
                // deep-link contract) — re-render it from surviving history.
                ContinueReadingWidget.refreshSafely()
                Toast.makeText(
                    this@MainActivity, R.string.continue_reading_unavailable, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            startActivity(GalleryOpenHelper.buildReadIntent(this@MainActivity, archive))
        }
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
        var savedState = savedInstanceState
        val intent = intent
        if (intent != null) {
            val res = intent.getBooleanExtra(SplashActivity.KEY_RESTART, false)
            if (res) {
                savedState = null
            }
        }
        setContentView(R.layout.activity_main)

        maybeRequestNotificationPermission()

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
        val mChangeTheme = ViewUtils.`$$`(this, R.id.change_theme) as TextView

        drawerLayout.setStatusBarColor(
            ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimaryDark)
        )

        navView.setNavigationItemSelectedListener(this)
        bindNavHeader(navView)

        // Theme-toggle row styling lives entirely in the layout (theme-aware
        // attrs: serverActiveNameColor / textColorSecondary / dividerColor),
        // so Light/Dark/Black need no per-theme color branching here.
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

        purgeLegacyHeaderCustomization()

        // After a language-switch process restart, take the user back to the
        // screen the switch was made on (Advanced settings) instead of
        // stranding them on the home scene. One-shot flag written just before
        // the restart, so ordinary launches never enter this branch.
        if (AppearanceSettings.consumeLanguageRestartRoute()) {
            settingsLauncher.launch(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(
                        SettingsActivity.KEY_INITIAL_SCREEN,
                        SettingsActivity.SCREEN_ADVANCED
                    )
            )
        }
    }

    /**
     * Binds the drawer header: static app version line plus the active-server
     * pill. The pill tracks the active [ServerProfile] via Room — it re-emits
     * on any profile-table change (rename, URL edit, switch, delete), so the
     * header self-updates without an explicit event channel.
     */
    private fun bindNavHeader(navView: NavigationView) {
        val header = navView.getHeaderView(0)
        header.findViewById<TextView>(R.id.nav_header_version)?.text =
            getString(R.string.nav_header_version_format, BuildConfig.VERSION_NAME)
        val serverLine = header.findViewById<TextView>(R.id.nav_header_server) ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceRegistry.dataModule.profileRepository.observeAll()
                    .map { profiles -> profiles.firstOrNull { it.isActive } }
                    .distinctUntilChanged()
                    .collect { active ->
                        serverLine.text = active?.let(::formatServerLine)
                            ?: getString(R.string.lrr_no_servers)
                    }
            }
        }
    }

    private fun formatServerLine(profile: ServerProfile): String {
        val parsed = runCatching { parseBaseUrl(profile.url) }.getOrNull()
        val host = parsed?.host.orEmpty()
        val port = parsed?.port?.takeIf { it != 80 && it != 443 }
        val hostPort = if (port != null) "$host:$port" else host
        return if (hostPort.isEmpty() || profile.name.contains(hostPort)) {
            profile.name
        } else {
            "${profile.name} · $hostPort"
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
     * Restart the whole app process, finishing this task's activities first so the
     * relaunch starts from a clean stack. The process-rebirth mechanics live in
     * [EhApplication.restart].
     */
    private fun triggerRebirth() {
        finishAffinity()
        (application as EhApplication).restart()
    }

    override fun onStart() {
        super.onStart()
        // LANraragi: EhViewer auto-update check disabled
        maybeShowDownloadResumeBanner()
    }

    /**
     * On return to the foreground, surface any downloads that paused waiting for
     * the network or gave up after the wait timeout. One-shot (consume()).
     */
    private fun maybeShowDownloadResumeBanner() {
        // Note: if app-lock is active, this fires in onStart() before SecurityScene
        // is pushed in onResume(), so the Snackbar appears briefly behind the lock and
        // its consumed state will not re-show after unlock. Accepted v1 limitation.
        val host = mDrawerLayout ?: return
        when (val snapshot = DownloadResumeBanner.consume()) {
            is DownloadResumeBanner.Snapshot.Paused -> {
                Snackbar.make(
                    host,
                    getString(R.string.download_resume_paused_snackbar),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.download_resume_view_action) {
                    startScene(Announcer(DownloadsScene::class.java))
                }.show()
            }
            is DownloadResumeBanner.Snapshot.TimedOut -> {
                Snackbar.make(
                    host,
                    resources.getQuantityString(
                        R.plurals.download_resume_timed_out_snackbar, snapshot.count, snapshot.count
                    ),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.download_resume_retry_action) {
                    val intent = Intent(this, DownloadService::class.java)
                        .setAction(DownloadService.ACTION_START_RANGE)
                        .putStringArrayListExtra(
                            DownloadService.KEY_ARCID_LIST,
                            ArrayList(snapshot.arcids),
                        )
                    startService(intent)
                }.show()
            }
            DownloadResumeBanner.Snapshot.None -> { /* nothing to show */ }
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
                UpdateDialog.showUpdateDialog(this, release)
            }
            .show()
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
            R.id.nav_tankoubons -> startScene(Announcer(TankoubonsScene::class.java))
            R.id.nav_history -> startScene(Announcer(HistoryScene::class.java))
            R.id.nav_downloads -> startScene(Announcer(DownloadsScene::class.java))
            R.id.nav_stats -> startScene(
                Announcer(com.hippo.ehviewer.ui.scene.stats.ReadingStatsScene::class.java)
            )
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
