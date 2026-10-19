package com.lanraragi.reader.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.navigation.NavigationView
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.parseBaseUrl
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.LRRCategoriesScene
import com.lanraragi.reader.ui.scene.ServerConfigScene
import com.lanraragi.reader.ui.scene.ServerListScene
import com.lanraragi.reader.ui.scene.TankoubonsScene
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.ui.scene.history.HistoryScene
import com.lanraragi.reader.ui.scene.stats.ReadingStatsScene
import com.lanraragi.reader.widget.AppDrawerLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity's left drawer: the header (app version and active-server
 * line), the theme row, the display-cutout insets of the drawer layout and
 * what each navigation item opens.
 */
class MainNavDrawer(
    private val activity: MainActivity,
    private val openSettings: () -> Unit,
    private val closeDrawers: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Strip display cutout insets on left/right so fitsSystemWindows doesn't pad
     * for the notch/punch-hole area in landscape. Top/bottom are preserved for
     * status bar (portrait) and navigation bar.
     */
    fun applyCutoutInsets(drawerLayout: AppDrawerLayout) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
    }

    /**
     * Binds the drawer header: static app version line plus the active-server
     * pill. The pill tracks the active [ServerProfile] via Room — it re-emits
     * on any profile-table change (rename, URL edit, switch, delete), so the
     * header self-updates without an explicit event channel.
     */
    fun bindHeader(navView: NavigationView) {
        val header = navView.getHeaderView(0)
        header.findViewById<TextView>(R.id.nav_header_version)?.text =
            activity.getString(R.string.nav_header_version_format, BuildConfig.VERSION_NAME)
        val serverLine = header.findViewById<TextView>(R.id.nav_header_server) ?: return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceRegistry.dataModule.profileRepository.observeAll()
                    .map { profiles -> profiles.firstOrNull { it.isActive } }
                    .distinctUntilChanged()
                    .collect { active -> bindServerLine(serverLine, active) }
            }
        }
    }

    /**
     * "name · host:port", ellipsized at the end, with an open padlock after it
     * when the server is plain HTTP — said where the active server is always
     * visible (audit C27). The padlock's meaning is in the content description.
     */
    private fun bindServerLine(serverLine: TextView, active: ServerProfile?) {
        val line = active?.let(::formatServerLine) ?: activity.getString(R.string.lrr_no_servers)
        val unencrypted = active != null && LRRUrlHelper.isUnencrypted(active.url)
        serverLine.text = line
        val dot = serverLine.compoundDrawablesRelative[0]
        val lock = if (unencrypted) ContextCompat.getDrawable(activity, R.drawable.v_lock_open_x14) else null
        serverLine.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, lock, null)
        serverLine.contentDescription = if (unencrypted) {
            "$line · ${activity.getString(R.string.lrr_unencrypted_connection)}"
        } else {
            null
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
     * Theme-toggle row styling lives entirely in the layout (theme-aware
     * attrs: serverActiveNameColor / textColorSecondary / dividerColor),
     * so Light/Dark/Black need no per-theme color branching here.
     */
    fun bindThemeRow(changeTheme: TextView) {
        changeTheme.text = getThemeText()
        changeTheme.setOnClickListener {
            AppearanceSettings.putTheme(getNextTheme())
            (activity.application as LRReaderApplication).recreate()
        }
    }

    private fun getThemeText(): String {
        val resId = when (AppearanceSettings.getTheme()) {
            AppearanceSettings.THEME_DARK -> R.string.theme_dark
            AppearanceSettings.THEME_BLACK -> R.string.theme_black
            else -> R.string.theme_light
        }
        return activity.getString(resId)
    }

    private fun getNextTheme(): Int = when (AppearanceSettings.getTheme()) {
        AppearanceSettings.THEME_DARK -> AppearanceSettings.THEME_BLACK
        AppearanceSettings.THEME_BLACK -> AppearanceSettings.THEME_LIGHT
        else -> AppearanceSettings.THEME_DARK
    }

    @SuppressLint("NonConstantResourceId")
    fun onItemSelected(item: MenuItem): Boolean {
        // Don't select twice
        if (item.isChecked) {
            return false
        }

        val id = item.itemId

        when (item.itemId) {
            R.id.nav_homepage -> {
                val navHomepage = Bundle()
                navHomepage.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
                activity.startSceneFirstly(
                    Announcer(GalleryListScene::class.java).setArgs(navHomepage)
                )
            }
            R.id.nav_favourite -> activity.startScene(Announcer(LRRCategoriesScene::class.java))
            R.id.nav_tankoubons -> activity.startScene(Announcer(TankoubonsScene::class.java))
            R.id.nav_history -> activity.startScene(Announcer(HistoryScene::class.java))
            R.id.nav_downloads -> activity.startScene(Announcer(DownloadsScene::class.java))
            R.id.nav_stats -> activity.startScene(Announcer(ReadingStatsScene::class.java))
            R.id.nav_settings -> openSettings()
            R.id.nav_server_config -> {
                // Show server list if profiles exist, otherwise direct to config
                activity.lifecycleScope.launch {
                    try {
                        val profileCount = withContext(ioDispatcher) {
                            ServiceRegistry.dataModule.profileRepository.getAllProfiles().size
                        }
                        if (profileCount > 0) {
                            activity.startScene(Announcer(ServerListScene::class.java))
                        } else {
                            activity.startScene(Announcer(ServerConfigScene::class.java))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load server profiles", e)
                    }
                }
            }
        }

        if (id != R.id.nav_stub) {
            closeDrawers()
        }

        return true
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
