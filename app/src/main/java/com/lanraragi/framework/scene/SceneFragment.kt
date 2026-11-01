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

@file:Suppress("EmptyFunctionBlock") // TODO(2.4-idiom)

package com.lanraragi.framework.scene

import android.os.Bundle
import android.view.View
import androidx.annotation.IntDef
import androidx.fragment.app.Fragment
import com.lanraragi.reader.R
import com.lanraragi.reader.util.IntList
import com.lanraragi.reader.util.attrDrawable
import kotlin.math.min

open class SceneFragment : Fragment() {

    @IntDef(LAUNCH_MODE_STANDARD, LAUNCH_MODE_SINGLE_TOP, LAUNCH_MODE_SINGLE_TASK)
    @Retention(AnnotationRetention.SOURCE)
    annotation class LaunchMode

    private var resultCode = RESULT_CANCELED
    private var result: Bundle? = null

    private val mRequestSceneTagList: MutableList<String?> = ArrayList(0)
    private val mRequestCodeList = IntList(0)

    open fun onNewArguments(args: Bundle) {}

    fun startScene(announcer: Announcer) {
        val activity = activity
        if (activity is StageActivity) {
            activity.startScene(announcer)
        }
    }

    fun finish() {
        finish(null)
    }

    fun finish(transitionHelper: TransitionHelper?) {
        val activity = activity
        if (activity is StageActivity) {
            activity.finishScene(this, transitionHelper)
        }
    }

    open fun onBackPressed() {
        finish()
    }

    /**
     * Whether back currently means something inside this scene (leave
     * multi-select, collapse a search bar, ...) instead of leaving it. Every
     * [onBackPressed] override that sometimes stays in the scene must
     * report those states here: the stage gives such a back no predictive
     * animation, and on the root scene it hands a back that means "leave" to
     * the system (back-to-home preview).
     */
    open fun hasInSceneBack(): Boolean = false

    /** Tells the stage that [hasInSceneBack] may have changed. */
    fun invalidateBack() {
        val activity = activity
        if (activity is StageActivity) {
            activity.updateBackCallback()
        }
    }

    @Suppress("DEPRECATION") // TODO(2.4-idiom)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.setTag(R.id.fragment_tag, tag)
        view.setBackgroundDrawable(requireContext().attrDrawable(android.R.attr.windowBackground))

        // Notify
        val activity = activity
        if (activity is StageActivity) {
            activity.dispatchSceneViewCreated(this, savedInstanceState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Notify
        val activity = activity
        if (activity is StageActivity) {
            activity.dispatchSceneViewDestroyed(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        val activity = activity
        if (activity is StageActivity) {
            activity.onSceneDestroyed(this)
        }
    }

    @JvmName("addRequest")
    internal fun addRequest(requestSceneTag: String?, requestCode: Int) {
        mRequestSceneTagList.add(requestSceneTag)
        mRequestCodeList.add(requestCode)
    }

    @JvmName("returnResult")
    internal fun returnResult(stage: StageActivity) {
        val size = min(mRequestSceneTagList.size, mRequestCodeList.size())
        for (i in 0 until size) {
            val tag = mRequestSceneTagList[i]
            val code = mRequestCodeList.get(i)
            val scene = stage.findSceneByTag(tag)
            scene?.onSceneResult(code, resultCode, result)
        }
        mRequestSceneTagList.clear()
        mRequestCodeList.clear()
    }

    protected open fun onSceneResult(requestCode: Int, resultCode: Int, data: Bundle?) {
    }

    fun setResult(resultCode: Int, result: Bundle?) {
        this.resultCode = resultCode
        this.result = result
    }

    companion object {
        const val LAUNCH_MODE_STANDARD = 0
        const val LAUNCH_MODE_SINGLE_TOP = 1
        const val LAUNCH_MODE_SINGLE_TASK = 2

        /** Standard scene result: operation canceled. */
        const val RESULT_CANCELED = 0

        /** Standard scene result: operation succeeded. */
        const val RESULT_OK = -1
    }
}
