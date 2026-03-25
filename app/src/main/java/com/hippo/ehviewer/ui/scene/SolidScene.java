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

package com.hippo.ehviewer.ui.scene;

import android.os.Bundle;
import android.util.Log;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.scene.Announcer;

/**
 * Scene for safety, can't be covered.
 *
 * LANraragi check-step flow:
 *   SECURITY → SERVER_CONFIG (if not configured) → GalleryListScene
 *
 * All E-Hentai steps (Warning, Analytics, SignIn, SelectSite) are removed.
 */
public class SolidScene extends BaseScene {

    private static final String TAG = SolidScene.class.getSimpleName();

    // Only keep security and sign-in steps; repurpose SIGN_IN for ServerConfig
    public static final int CHECK_STEP_SECURITY = 0;
    public static final int CHECK_STEP_WARNING = 1;   // skipped
    public static final int CHECK_STEP_ANALYTICS = 2;  // skipped
    public static final int CHECK_STEP_SIGN_IN = 3;    // = ServerConfig
    public static final int CHECK_STEP_SELECT_SITE = 4; // skipped

    public static final String KEY_TARGET_SCENE = "target_scene";
    public static final String KEY_TARGET_ARGS = "target_args";

    public void startSceneForCheckStep(int checkStep, Bundle args) {
        switch (checkStep) {
            case CHECK_STEP_SECURITY:
                // LANraragi: skip Warning and Analytics, go straight to server config check
            case CHECK_STEP_WARNING:
            case CHECK_STEP_ANALYTICS:
                // If LANraragi server is not configured, show ServerConfigScene
                if (!LRRAuthManager.isConfigured()) {
                    startScene(new Announcer(ServerConfigScene.class).setArgs(args));
                    break;
                }
                // Fall through to target scene
            case CHECK_STEP_SIGN_IN:
            case CHECK_STEP_SELECT_SITE:
                String targetScene = null;
                Bundle targetArgs = null;
                if (null != args) {
                    targetScene = args.getString(KEY_TARGET_SCENE);
                    targetArgs = args.getBundle(KEY_TARGET_ARGS);
                }

                Class<?> clazz = null;
                if (targetScene != null) {
                    try {
                        clazz = Class.forName(targetScene);
                    } catch (ClassNotFoundException e) {
                        Log.e(TAG, "Can't find class with name: " + targetScene);
                    }
                }

                if (clazz != null) {
                    startScene(new Announcer(clazz).setArgs(targetArgs));
                } else {
                    Bundle newArgs = new Bundle();
                    newArgs.putString(GalleryListScene.KEY_ACTION,
                            GalleryListScene.ACTION_HOMEPAGE);
                    startScene(new Announcer(GalleryListScene.class).setArgs(newArgs));
                }
                break;
        }
    }
}
