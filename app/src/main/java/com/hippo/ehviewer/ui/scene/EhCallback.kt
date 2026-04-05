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

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.scene.SceneFragment
import com.hippo.scene.StageActivity

abstract class EhCallback<E : SceneFragment?, T>(
    context: Context?,
    private val mStageId: Int,
    private val mSceneTag: String?
) : EhClient.Callback<T> {

    @JvmField
    val mApplication: EhApplication = context?.applicationContext as EhApplication

    abstract fun isInstance(scene: SceneFragment?): Boolean

    open val content: Context
        get() = stageActivity ?: application

    // Java-compatible getter
    open fun getContent(): Context = content

    open val application: EhApplication
        get() = mApplication

    open val stageActivity: StageActivity?
        get() = mApplication.findStageActivityById(mStageId)

    @Suppress("UNCHECKED_CAST")
    open val scene: E?
        get() {
            val stage = mApplication.findStageActivityById(mStageId) ?: return null
            val sceneFragment = stage.findSceneByTag(mSceneTag) ?: return null
            return if (isInstance(sceneFragment)) {
                sceneFragment as E
            } else {
                null
            }
        }

    open fun showTip(@StringRes id: Int, length: Int) {
        val activity = stageActivity
        if (activity is MainActivity) {
            activity.showTip(id, length)
        } else {
            Toast.makeText(
                application, id,
                if (length == BaseScene.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }

    open fun showTip(tip: String, length: Int) {
        val activity = stageActivity
        if (activity is MainActivity) {
            activity.showTip(tip, length)
        } else {
            Toast.makeText(
                application, tip,
                if (length == BaseScene.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }
}
