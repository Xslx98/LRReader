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

package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.util.Log
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer

/**
 * Scene for safety, can't be covered.
 *
 * LANraragi check-step flow:
 *   SECURITY -> SERVER_CONFIG (if not configured) -> GalleryListScene
 *
 * All E-Hentai steps (Warning, Analytics, SignIn, SelectSite) are removed.
 */
open class SolidScene : BaseScene() {

    companion object {
        private val TAG = SolidScene::class.java.simpleName

        // Only keep security and sign-in steps; repurpose SIGN_IN for ServerConfig
        const val CHECK_STEP_SECURITY = 0
        const val CHECK_STEP_WARNING = 1    // skipped
        const val CHECK_STEP_ANALYTICS = 2  // skipped
        const val CHECK_STEP_SIGN_IN = 3    // = ServerConfig
        const val CHECK_STEP_SELECT_SITE = 4 // skipped

        @JvmField
        val KEY_TARGET_SCENE = "target_scene"
        @JvmField
        val KEY_TARGET_ARGS = "target_args"
    }

    fun startSceneForCheckStep(checkStep: Int, args: Bundle?) {
        when (checkStep) {
            CHECK_STEP_SECURITY,
            CHECK_STEP_WARNING,
            CHECK_STEP_ANALYTICS -> {
                // LANraragi: skip Warning and Analytics, go straight to server config check
                // If LANraragi server is not configured, show ServerConfigScene
                if (!LRRAuthManager.isConfigured()) {
                    startScene(Announcer(ServerConfigScene::class.java).setArgs(args))
                    return
                }
                // Fall through to target scene
                navigateToTarget(args)
            }
            CHECK_STEP_SIGN_IN,
            CHECK_STEP_SELECT_SITE -> {
                navigateToTarget(args)
            }
        }
    }

    private fun navigateToTarget(args: Bundle?) {
        var targetScene: String? = null
        var targetArgs: Bundle? = null
        if (args != null) {
            targetScene = args.getString(KEY_TARGET_SCENE)
            targetArgs = args.getBundle(KEY_TARGET_ARGS)
        }

        var clazz: Class<*>? = null
        if (targetScene != null) {
            try {
                clazz = Class.forName(targetScene)
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "Can't find class with name: $targetScene")
            }
        }

        if (clazz != null) {
            startScene(Announcer(clazz).setArgs(targetArgs))
        } else {
            val newArgs = Bundle()
            newArgs.putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_HOMEPAGE)
            startScene(Announcer(GalleryListScene::class.java).setArgs(newArgs))
        }
    }
}
