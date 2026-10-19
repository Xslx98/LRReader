package com.lanraragi.reader.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.R
import com.lanraragi.reader.client.LRRUrlOpener
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ui.scene.SecurityScene
import com.lanraragi.reader.ui.scene.ServerConfigScene
import com.lanraragi.reader.ui.scene.SolidScene
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene

/**
 * Where MainActivity's incoming intents and intent-started scenes go: links,
 * shared text and the continue-reading deep link, routed through the app-lock
 * and server-config gates, and deferred while a gate scene is on top.
 */
class MainIntentRouter(private val activity: MainActivity) {

    // ── Deferred deep links while a gate scene shows (audit #57) ──

    /** Scene-start request deferred while a SolidScene gate was showing. */
    private var pendingGateAnnouncer: Announcer? = null

    /** Recognized intent deferred while a SolidScene gate was showing. */
    private var pendingGateIntent: Intent? = null

    // LANraragi: simplified -- only security gate and server config gate remain
    private fun processAnnouncer(announcer: Announcer): Announcer {
        if (activity.sceneCount == 0) {
            if (SecuritySettings.isLockEnabled()) {
                val newArgs = Bundle()
                newArgs.putString(SolidScene.KEY_TARGET_SCENE, announcer.clazz.name)
                newArgs.putBundle(SolidScene.KEY_TARGET_ARGS, announcer.args)
                return Announcer(SecurityScene::class.java).setArgs(newArgs)
            } else if (!LRRAuthManager.isConfiguredFast()) {
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

        return when (intent.action) {
            ContinueReadingShortcut.ACTION_CONTINUE_READING -> {
                val arcid = intent.getStringExtra(ContinueReadingShortcut.KEY_ARCID)
                val profileId = intent.getLongExtra(ContinueReadingShortcut.KEY_PROFILE_ID, -1L)
                if (!arcid.isNullOrEmpty() && profileId > 0) {
                    ContinueReadingLauncher.open(activity, arcid, profileId)
                }
                // Deliberately fall through as "unhandled": the default scene
                // stack builds underneath and the reader lands on top, so BACK
                // from the reader returns to the main list.
                false
            }
            Intent.ACTION_VIEW -> {
                val announcer = intent.data?.let { LRRUrlOpener.parseUrl(it.toString()) }
                if (announcer != null) {
                    activity.startScene(processAnnouncer(announcer))
                    true
                } else {
                    false
                }
            }
            // Shared images used to launch the EhViewer image search; LANraragi
            // has no image-search API, so only shared text becomes a keyword search.
            Intent.ACTION_SEND -> if ("text/plain" == intent.type) {
                val builder = ListUrlBuilder()
                builder.keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                activity.startScene(processAnnouncer(GalleryListScene.getStartAnnouncer(builder)))
                true
            } else {
                false
            }
            else -> false
        }
    }

    fun onUnrecognizedIntent(intent: Intent?) {
        if (isGateSceneShowing()) {
            // Defer while the security/config gate is showing; replayed by
            // maybeReplayPendingGateRequest once the gate clears (#57).
            if (intent != null && intent.action != null) {
                pendingGateIntent = intent
                pendingGateAnnouncer = null
            }
            return
        }

        if (!handleIntent(intent)) {
            var handleUrl = false
            if (intent != null && Intent.ACTION_VIEW == intent.action) {
                handleUrl = true
                Toast.makeText(activity, R.string.error_cannot_parse_the_url, Toast.LENGTH_SHORT).show()
            }

            if (activity.sceneCount == 0) {
                if (handleUrl) {
                    activity.finish()
                } else {
                    val args = Bundle()
                    args.putString(
                        GalleryListScene.KEY_ACTION,
                        AppearanceSettings.getLaunchPageGalleryListSceneAction()
                    )
                    activity.startScene(
                        processAnnouncer(Announcer(GalleryListScene::class.java).setArgs(args))
                    )
                }
            }
        }
    }

    fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): Announcer? {
        // App-lock guard: while a SolidScene gate (SecurityScene lock prompt /
        // ServerConfigScene) is showing, starting the requested scene would
        // remove or bury the gate WITHOUT an unlock — most target scenes are
        // LAUNCH_MODE_SINGLE_TASK, so StageActivity.startScene pops everything
        // above an existing instance, lock prompt included. That let a
        // notification tap bypass the pattern lock straight from the shade.
        // Defer instead of dropping: the request replays once the gate clears
        // (successful unlock / completed config).
        if (isGateSceneShowing()) {
            pendingGateAnnouncer = Announcer(clazz).setArgs(args)
            pendingGateIntent = null
            return null
        }
        return processAnnouncer(Announcer(clazz).setArgs(args))
    }

    private fun isGateSceneShowing(): Boolean {
        val top = activity.topSceneClass
        return top != null && SolidScene::class.java.isAssignableFrom(top)
    }

    /**
     * Replay a request that arrived while the security/config gate was
     * showing. Called (posted) after every scene transaction: once the gate
     * is no longer on top — the only ways off the stack are a successful
     * unlock or a completed server config — the buffered request runs
     * through the normal (re-gated) dispatch paths.
     */
    fun maybeReplayPendingGateRequest() {
        if (isGateSceneShowing()) return
        val announcer = pendingGateAnnouncer
        val intent = pendingGateIntent
        pendingGateAnnouncer = null
        pendingGateIntent = null
        when {
            announcer != null -> activity.startScene(processAnnouncer(announcer))
            intent != null -> handleIntent(intent)
        }
    }
}
