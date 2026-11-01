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

@file:Suppress(
    "ComplexCondition", "CyclomaticComplexMethod", "EmptyFunctionBlock", "LargeClass", "LongMethod",
    "NestedBlockDepth", "ReturnCount", "TooGenericExceptionThrown", "UseCheckOrError",
) // TODO(2.4-idiom)

package com.lanraragi.framework.scene

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.BackDispatch
import com.lanraragi.reader.ui.BaseActivity
import com.lanraragi.reader.util.AssertUtils
import com.lanraragi.reader.util.IntIdGenerator
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

abstract class StageActivity : BaseActivity() {

    private val sceneTagList = ArrayList<String>()
    private val delaySceneTagList = ArrayList<String>()
    private val idGenerator = AtomicInteger()

    private var stageId = IntIdGenerator.INVALID_ID

    // Enabled only while the app has a use for back (see handlesBack()):
    // otherwise the system takes it and shows its back-to-home preview.
    // Subclasses hook in through hasInterceptBack() / onInterceptBack().
    private val backCallback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            startPredictiveBack()
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (predictTop != null) {
                applyPredictiveBack(backEvent.progress, 0f)
            }
        }

        override fun handleOnBackCancelled() {
            cancelPredictiveBack()
        }

        override fun handleOnBackPressed() {
            if (predictTop != null) {
                commitPredictiveBack()
            } else {
                dispatchBack()
            }
        }
    }

    // Predictive back between scenes: the scene being dragged away, the one
    // revealed under it, and the settle animation after the gesture ends.
    private var predictTop: SceneFragment? = null
    private var predictBelow: Fragment? = null
    private var predictProgress = 0f
    private var predictAttachedBelow = false
    private var predictBelowViewPending = false
    private var predictAnimator: ValueAnimator? = null

    private val updateBackCallbackTask = Runnable { updateBackCallback() }

    /** Set by findTopScene(true) when it dropped a tag without a scene. */
    private var droppedStaleTag = false

    /** finishScene without the close animation (a predictive back already played one). */
    private var skipCloseAnimation = false

    private val sceneViewComparator = SceneViewComparator()

    private inner class SceneViewComparator : Comparator<View> {

        private fun getIndex(view: View): Int {
            val o = view.getTag(R.id.fragment_tag)
            return if (o is String) {
                delaySceneTagList.indexOf(o)
            } else {
                -1
            }
        }

        override fun compare(lhs: View, rhs: View): Int = getIndex(lhs) - getIndex(rhs)
    }

    abstract val containerViewId: Int

    /**
     * @return `true` for start scene
     */
    private fun startSceneFromIntent(intent: Intent): Boolean {
        val clazzStr = intent.getStringExtra(KEY_SCENE_NAME) ?: return false
        // Checked BEFORE Class.forName: the name may come from another app,
        // and loading an arbitrary class runs its static initialisers.
        if (!acceptSceneIntent(intent, clazzStr)) {
            Log.w(TAG, "Rejected start_scene intent")
            return false
        }

        val clazz: Class<*>
        try {
            clazz = Class.forName(clazzStr)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Can't find class $clazzStr", e)
            return false
        }

        val args = intent.getBundleExtra(KEY_SCENE_ARGS)

        val announcer = onStartSceneFromIntent(clazz, args) ?: return false

        try {
            startScene(announcer)
        } catch (e: RuntimeException) {
            // An unstartable scene (e.g. not registered) must never crash
            // the app from an intent.
            Log.e(TAG, "Can't start scene from intent", e)
            return false
        }
        return true
    }

    /**
     * Whether a `start_scene` intent naming `clazzName` may be
     * honoured at all. The intent may come from any app when this activity
     * is exported. Default: accept.
     */
    protected open fun acceptSceneIntent(intent: Intent, clazzName: String): Boolean = true

    /**
     * Start scene from `Intent`, it might be not safe,
     * Correct it here.
     *
     * @return `null` for do not start scene
     */
    protected open fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): Announcer? =
        Announcer(clazz).setArgs(args)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (ACTION_START_SCENE != intent.action || !startSceneFromIntent(intent)) {
            onUnrecognizedIntent(intent)
        }
    }

    /**
     * Called when launch with action `android.intent.action.MAIN`
     */
    protected abstract fun getLaunchAnnouncer(): Announcer?

    /**
     * Can't recognize intent in first time `onCreate` and `onNewIntent`,
     * null included.
     */
    protected open fun onUnrecognizedIntent(intent: Intent?) {}

    /**
     * Call `setContentView` here. Do **NOT** call `startScene` here
     */
    protected abstract fun onCreate2(savedInstanceState: Bundle?)

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState != null) {
            stageId = savedInstanceState.getInt(KEY_STAGE_ID, IntIdGenerator.INVALID_ID)
            val list = savedInstanceState.getStringArrayList(KEY_SCENE_TAG_LIST)
            if (list != null) {
                sceneTagList.addAll(list)
                delaySceneTagList.addAll(list)
            }
            idGenerator.lazySet(savedInstanceState.getInt(KEY_NEXT_ID))
        }

        if (stageId == IntIdGenerator.INVALID_ID) {
            (applicationContext as SceneApplication).registerStageActivity(this)
        } else {
            (applicationContext as SceneApplication).registerStageActivity(this, stageId)
        }

        // Create layout
        onCreate2(savedInstanceState)

        val intent: Intent? = intent
        if (savedInstanceState == null) {
            if (intent != null) {
                val action = intent.action
                if (Intent.ACTION_MAIN == action) {
                    val announcer = getLaunchAnnouncer()
                    if (announcer != null) {
                        startScene(announcer)
                        return
                    }
                } else if (ACTION_START_SCENE == action) {
                    if (startSceneFromIntent(intent)) {
                        return
                    }
                }
            }

            // Can't recognize intent
            onUnrecognizedIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STAGE_ID, stageId)
        outState.putStringArrayList(KEY_SCENE_TAG_LIST, sceneTagList)
        outState.putInt(KEY_NEXT_ID, idGenerator.getAndIncrement())
    }

    override fun onDestroy() {
        super.onDestroy()
        (applicationContext as SceneApplication).unregisterStageActivity(stageId)
    }

    open fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
    }

    open fun onSceneViewDestroyed(scene: SceneFragment) {
    }

    @JvmName("onSceneDestroyed")
    internal fun onSceneDestroyed(scene: SceneFragment) {
        val tag = scene.tag
        if (tag != null) {
            delaySceneTagList.remove(tag)
        }
    }

    @JvmName("onRegister")
    internal fun onRegister(id: Int) {
        stageId = id
    }

    @JvmName("onUnregister")
    internal fun onUnregister() {
    }

    protected open fun onTransactScene() {
    }

    val sceneCount: Int
        get() = sceneTagList.size

    fun getSceneLaunchMode(clazz: Class<*>): Int =
        launchModes[clazz] ?: throw RuntimeException("Not register " + clazz.name)

    private fun newSceneInstance(clazz: Class<*>): SceneFragment = SceneFactory.create(clazz.name)

    fun startScene(announcer: Announcer) {
        val clazz = announcer.clazz
        val args = announcer.args
        val tranHelper = announcer.tranHelper
        val fragmentManager = supportFragmentManager
        val launchMode = getSceneLaunchMode(clazz)

        // Check LAUNCH_MODE_SINGLE_TASK
        if (launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TASK) {
            val n = sceneTagList.size
            for (i in 0 until n) {
                val tag = sceneTagList[i]
                val fragment = fragmentManager.findFragmentByTag(tag)
                if (fragment == null) {
                    Log.e(TAG, "Can't find fragment with tag: $tag")
                    continue
                }

                if (clazz.isInstance(fragment)) { // Get it
                    val transaction = fragmentManager.beginTransaction()

                    // Use default animation
                    transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit)

                    // Remove top fragments
                    for (j in i + 1 until n) {
                        val topTag = sceneTagList[j]
                        val topFragment = fragmentManager.findFragmentByTag(topTag)
                        if (null == topFragment) {
                            Log.e(TAG, "Can't find fragment with tag: $topTag")
                            continue
                        }
                        // Clear shared element
                        topFragment.sharedElementEnterTransition = null
                        topFragment.sharedElementReturnTransition = null
                        topFragment.enterTransition = null
                        topFragment.exitTransition = null
                        // Remove it
                        transaction.remove(topFragment)
                    }

                    // Remove tag from index i+1
                    sceneTagList.subList(i + 1, sceneTagList.size).clear()

                    // Attach fragment
                    if (fragment.isDetached) {
                        transaction.attach(fragment)
                    }

                    // Commit
                    transaction.commitAllowingStateLoss()
                    onTransactScene()
                    postUpdateBackCallback()

                    // New arguments
                    if (args != null && fragment is SceneFragment) {
                        fragment.onNewArguments(args)
                    }

                    return
                }
            }
        }

        // Get current fragment
        var currentScene: SceneFragment? = null
        if (sceneTagList.size > 0) {
            // Get last tag
            val tag = sceneTagList[sceneTagList.size - 1]
            val fragment = fragmentManager.findFragmentByTag(tag)
            if (fragment != null) {
                AssertUtils.assertTrue(SceneFragment::class.java.isInstance(fragment))
                currentScene = fragment as SceneFragment
            }
        }

        // Check LAUNCH_MODE_SINGLE_TASK
        if (currentScene != null && clazz.isInstance(currentScene) &&
            launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TOP
        ) {
            if (args != null) {
                currentScene.onNewArguments(args)
            }
            return
        }

        // Create new scene
        val newScene = newSceneInstance(clazz)
        newScene.arguments = args

        // Create new scene tag
        val newTag = idGenerator.getAndIncrement().toString()

        // Add new tag to list
        sceneTagList.add(newTag)
        delaySceneTagList.add(newTag)

        val transaction = fragmentManager.beginTransaction()
        // Animation
        if (currentScene != null) {
            if (tranHelper == null || !tranHelper.onTransition(
                    this, transaction, currentScene, newScene
                )
            ) {
                // Clear shared item
                currentScene.sharedElementEnterTransition = null
                currentScene.sharedElementReturnTransition = null
                currentScene.enterTransition = null
                currentScene.exitTransition = null
                newScene.sharedElementEnterTransition = null
                newScene.sharedElementReturnTransition = null
                newScene.enterTransition = null
                newScene.exitTransition = null
                // Set default animation
                transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit)
            }
            // Detach current scene
            if (!currentScene.isDetached) {
                transaction.detach(currentScene)
            } else {
                Log.e(TAG, "Current scene is detached")
            }
        }

        // Add new scene
        transaction.add(containerViewId, newScene, newTag)

        // Commit
        transaction.commitAllowingStateLoss()
        onTransactScene()
        postUpdateBackCallback()

        // Check request
        val requestFrom = announcer.requestFrom
        if (requestFrom != null) {
            newScene.addRequest(requestFrom.tag, announcer.requestCode)
        }
    }

    fun startSceneFirstly(announcer: Announcer) {
        val clazz = announcer.clazz
        val args = announcer.args
        val fragmentManager = supportFragmentManager
        val launchMode = getSceneLaunchMode(clazz)
        val forceNewScene = launchMode == SceneFragment.LAUNCH_MODE_STANDARD
        var createNewScene = true
        var findScene = false
        var scene: SceneFragment? = null

        val transaction = fragmentManager.beginTransaction()

        // Set default animation
        transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit)

        var findSceneTag: String? = null
        for (i in 0 until sceneTagList.size) {
            val tag = sceneTagList[i]
            val fragment = fragmentManager.findFragmentByTag(tag)
            if (fragment == null) {
                Log.e(TAG, "Can't find fragment with tag: $tag")
                continue
            }

            // Clear shared element
            fragment.sharedElementEnterTransition = null
            fragment.sharedElementReturnTransition = null
            fragment.enterTransition = null
            fragment.exitTransition = null

            // Check is target scene
            if (!forceNewScene && !findScene && clazz.isInstance(fragment) &&
                (launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TASK || !fragment.isDetached)
            ) {
                scene = fragment as SceneFragment
                findScene = true
                createNewScene = false
                findSceneTag = tag
                if (fragment.isDetached) {
                    transaction.attach(fragment)
                }
            } else {
                // Remove it
                transaction.remove(fragment)
            }
        }

        // Handle tag list
        sceneTagList.clear()
        if (null != findSceneTag) {
            sceneTagList.add(findSceneTag)
        }

        if (createNewScene) {
            scene = newSceneInstance(clazz)
            scene.arguments = args

            // Create scene tag
            val tag = idGenerator.getAndIncrement().toString()

            // Add tag to list
            sceneTagList.add(tag)
            delaySceneTagList.add(tag)

            // Add scene
            transaction.add(containerViewId, scene, tag)
        }

        // Commit
        transaction.commitAllowingStateLoss()
        onTransactScene()
        postUpdateBackCallback()

        if (!createNewScene && args != null) {
            scene?.onNewArguments(args)
        }
    }

    @JvmName("sortSceneViews")
    internal fun sortSceneViews(views: List<View>) {
        Collections.sort(views, sceneViewComparator)
    }

    fun finishScene(scene: SceneFragment) {
        finishScene(scene, null)
    }

    fun finishScene(scene: SceneFragment, transitionHelper: TransitionHelper?) {
        finishScene(scene.tag, transitionHelper)
    }

    private fun finishScene(tag: String?, transitionHelper: TransitionHelper?) {
        val fragmentManager = supportFragmentManager

        // Get scene (a null tag finds none)
        val scene = fragmentManager.findFragmentByTag(tag)
        if (scene == null || tag == null) {
            Log.e(TAG, "finishScene: Can't find scene by tag: $tag")
            return
        }

        // Get scene index
        val index = sceneTagList.indexOf(tag)
        if (index < 0) {
            Log.e(TAG, "finishScene: Can't find the tag in tag list: $tag")
            return
        }

        if (sceneTagList.size == 1) {
            // It is the last fragment, finish Activity now
            Log.i(TAG, "finishScene: It is the last scene, finish activity now")
            finish()
            return
        }

        var next: Fragment? = null
        if (index == sceneTagList.size - 1) {
            // It is first fragment, show the next one
            next = fragmentManager.findFragmentByTag(sceneTagList[index - 1])
        }

        val transaction = fragmentManager.beginTransaction()
        if (next != null) {
            if (transitionHelper == null || !transitionHelper.onTransition(
                    this, transaction, scene, next
                )
            ) {
                // Clear shared item
                scene.sharedElementEnterTransition = null
                scene.sharedElementReturnTransition = null
                scene.enterTransition = null
                scene.exitTransition = null
                next.sharedElementEnterTransition = null
                next.sharedElementReturnTransition = null
                next.enterTransition = null
                next.exitTransition = null
                // Do not show animate if it is not the first fragment. A
                // predictive back has already animated both scenes.
                if (!skipCloseAnimation) {
                    transaction.setCustomAnimations(R.anim.scene_close_enter, R.anim.scene_close_exit)
                }
            }
            // Attach fragment
            transaction.attach(next)
        }
        transaction.remove(scene)
        transaction.commitAllowingStateLoss()
        onTransactScene()
        postUpdateBackCallback()

        // Remove tag
        sceneTagList.removeAt(index)

        // Return result
        if (scene is SceneFragment) {
            scene.returnResult(this)
        }
    }

    fun refreshTopScene() {
        val index = sceneTagList.size - 1
        if (index < 0) {
            return
        }
        val tag = sceneTagList[index]

        val fragmentManager = supportFragmentManager
        val fragment = fragmentManager.findFragmentByTag(tag)
        if (fragment == null) {
            // Tag list and FragmentManager can diverge after a post-save
            // commitAllowingStateLoss was dropped; detach(null) would NPE.
            Log.e(TAG, "refreshTopScene: Can't find fragment with tag: $tag")
            return
        }

        // Two transactions: a detach and an attach in the same one cancel out
        // and the view is never recreated (lint DetachAndAttachSameFragment).
        fragmentManager.beginTransaction().detach(fragment).commitAllowingStateLoss()
        fragmentManager.beginTransaction().attach(fragment).commitAllowingStateLoss()
        onTransactScene()
        postUpdateBackCallback()
    }

    /**
     * Back handling before the scene stack sees it (e.g. closing a drawer).
     *
     * @return `true` when consumed
     */
    protected open fun onInterceptBack(): Boolean = false

    /** Whether [onInterceptBack] would consume a back right now. */
    protected open fun hasInterceptBack(): Boolean = false

    /**
     * Whether the app has a use for back right now: something to intercept,
     * a scene to pop, or in-scene state on the root scene. When it has none,
     * back belongs to the system (back-to-home on the root task).
     */
    private fun handlesBack(): Boolean {
        if (hasInterceptBack()) {
            return true
        }
        if (sceneTagList.size > 1) {
            return true
        }
        val top = findTopScene(false)
        return top != null && top.hasInSceneBack()
    }

    /**
     * Re-reads [handlesBack] into the back callback. Called after
     * every scene transaction, every touch or key release (posted, so click
     * handlers have run) and from [SceneFragment.invalidateBack].
     */
    fun updateBackCallback() {
        backCallback.isEnabled = handlesBack()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val result = super.dispatchTouchEvent(ev)
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            postUpdateBackCallback()
        }
        return result
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val result = super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            postUpdateBackCallback()
        }
        return result
    }

    private fun postUpdateBackCallback() {
        val decor = window.decorView
        decor.removeCallbacks(updateBackCallbackTask)
        decor.post(updateBackCallbackTask)
    }

    override fun onResume() {
        super.onResume()
        updateBackCallback()
    }

    /**
     * The top scene, or null. With `repair` (only from a back event,
     * never from inside a fragment callback), pending transactions run first
     * and a tag whose fragment is still gone — the tag list and the
     * FragmentManager diverged after a dropped post-save commit — is dropped,
     * so back reaches the scene below instead of doing nothing (STAB-05).
     */
    private fun findTopScene(repair: Boolean): SceneFragment? {
        val fragmentManager = supportFragmentManager
        val canRepair = repair && !fragmentManager.isStateSaved
        if (canRepair) {
            fragmentManager.executePendingTransactions()
        }
        for (i in sceneTagList.size - 1 downTo 0) {
            val tag = sceneTagList[i]
            val fragment = fragmentManager.findFragmentByTag(tag)
            if (fragment is SceneFragment) {
                return fragment
            }
            if (!canRepair) {
                return null
            }
            Log.e(TAG, "Dropping scene tag without a scene: $tag")
            sceneTagList.removeAt(i)
            delaySceneTagList.remove(tag)
            droppedStaleTag = true
        }
        return null
    }

    /**
     * One back event: [onInterceptBack], then the top scene's
     * `onBackPressed` — unless the root scene has nothing of its own to
     * do, which hands the back to the system.
     */
    private fun dispatchBack() {
        if (onInterceptBack()) {
            updateBackCallback()
            return
        }
        droppedStaleTag = false
        val scene = findTopScene(true)
        if (scene != null && droppedStaleTag) {
            // The scene this back was meant to leave was already gone, so the
            // screen showed nothing: bring back the one below and stop here.
            if (scene.isDetached) {
                supportFragmentManager.beginTransaction().attach(scene).commitAllowingStateLoss()
            }
            postUpdateBackCallback()
            return
        }
        if (scene == null || (sceneTagList.size == 1 && !scene.hasInSceneBack())) {
            // Nothing of ours to go back to (also the URL-error finish path
            // with an empty stack): the system's back, i.e. home.
            BackDispatch.passThrough(onBackPressedDispatcher, backCallback)
            updateBackCallback()
            return
        }
        scene.onBackPressed()
        updateBackCallback()
    }

    // ======== Predictive back between scenes ========

    /**
     * A back gesture started. When it would simply pop the top scene, attach
     * the scene below now so the gesture can reveal it; anything else (a
     * drawer, in-scene state, the root scene) gets no preview.
     */
    private fun startPredictiveBack() {
        endPredictAnimator()
        if (hasInterceptBack() || sceneTagList.size < 2) {
            return
        }
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return
        }
        fragmentManager.executePendingTransactions()
        val top = findTopScene(false)
        if (top == null || top.hasInSceneBack() || top.view == null) {
            return
        }
        val belowTag = sceneTagList[sceneTagList.size - 2]
        val below = fragmentManager.findFragmentByTag(belowTag) ?: return
        predictTop = top
        predictBelow = below
        if (below.isDetached) {
            // Its view is created now but the stage's view hooks (the right
            // drawer belongs to the top scene meanwhile) wait for the commit.
            predictAttachedBelow = true
            fragmentManager.beginTransaction().attach(below).commitNow()
        }
        val topView = top.view
        // Attaching added the revealed view last, i.e. on top.
        topView?.bringToFront()
        applyPredictiveBack(0f, 0f)
    }

    /**
     * Places both scenes for gesture `progress` (0..1). `exit`
     * (0..1) runs after the gesture commits: the top scene fades out and
     * moves on to where the close animation would have left it.
     */
    private fun applyPredictiveBack(progress: Float, exit: Float) {
        predictProgress = progress
        val top = predictTop
        val below = predictBelow
        val topView = top?.view
        val belowView = below?.view
        if (topView != null) {
            val width = topView.width.toFloat()
            topView.translationX = width * (PREDICT_TOP_SHIFT * progress + PREDICT_TOP_EXIT_SHIFT * exit)
            topView.alpha = 1f - exit
        }
        if (belowView != null) {
            val width = belowView.width.toFloat()
            belowView.translationX = -width * PREDICT_BELOW_SHIFT * (1f - progress)
            belowView.alpha = PREDICT_BELOW_MIN_ALPHA + (1f - PREDICT_BELOW_MIN_ALPHA) * progress
        }
    }

    /** The gesture was released past the threshold: finish the slide, then pop. */
    private fun commitPredictiveBack() {
        val top = predictTop
        val from = predictProgress
        endPredictAnimator()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = settleDuration(1f - from)
        animator.addUpdateListener { a ->
            val f = a.animatedValue as Float
            applyPredictiveBack(from + (1f - from) * f, f)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                predictAnimator = null
                finishPredictiveBack(top)
            }
        })
        predictAnimator = animator
        animator.start()
    }

    /** Pops `top` without the close animation and hands the revealed scene its view hooks. */
    private fun finishPredictiveBack(top: SceneFragment?) {
        val below = predictBelow
        val belowViewPending = predictBelowViewPending
        resetPredictiveViews()
        predictTop = null
        predictBelow = null
        predictAttachedBelow = false
        predictBelowViewPending = false
        if (top == null || sceneTagList.isEmpty() ||
            top.tag != sceneTagList[sceneTagList.size - 1]
        ) {
            // The stack moved under the gesture; leave it as it is.
            updateBackCallback()
            return
        }
        skipCloseAnimation = true
        try {
            finishScene(top)
        } finally {
            skipCloseAnimation = false
        }
        val fragmentManager = supportFragmentManager
        if (!fragmentManager.isStateSaved) {
            fragmentManager.executePendingTransactions()
        }
        if (belowViewPending && below is SceneFragment && below.view != null) {
            onSceneViewCreated(below, null)
        }
    }

    /** The gesture was abandoned: slide back, then hide the revealed scene again. */
    private fun cancelPredictiveBack() {
        if (predictTop == null) {
            return
        }
        val from = predictProgress
        endPredictAnimator()
        val animator = ValueAnimator.ofFloat(from, 0f)
        animator.duration = settleDuration(from)
        animator.addUpdateListener { a -> applyPredictiveBack(a.animatedValue as Float, 0f) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                predictAnimator = null
                restoreAfterPredictiveBack()
            }
        })
        predictAnimator = animator
        animator.start()
    }

    private fun restoreAfterPredictiveBack() {
        val top = predictTop
        val below = predictBelow
        resetPredictiveViews()
        val fragmentManager = supportFragmentManager
        if (below != null && predictAttachedBelow && !below.isDetached &&
            !fragmentManager.isStateSaved
        ) {
            // Still predictBelow here, so its view-destroyed hook is skipped too.
            fragmentManager.beginTransaction().detach(below).commitNow()
        }
        predictTop = null
        predictBelow = null
        predictAttachedBelow = false
        predictBelowViewPending = false
        if (top != null) {
            onPredictiveBackCancelled(top)
        }
        updateBackCallback()
    }

    /** Runs a pending settle to its end at once (a new gesture, or the stage going away). */
    private fun endPredictAnimator() {
        predictAnimator?.end()
    }

    private fun resetPredictiveViews() {
        for (fragment in arrayOf<Fragment?>(predictTop, predictBelow)) {
            val view = fragment?.view
            if (view != null) {
                view.translationX = 0f
                view.alpha = 1f
            }
        }
    }

    /**
     * A predictive back was abandoned and `top` stays. The revealed
     * scene's own view setup (drawer lock, checked nav item) ran meanwhile;
     * subclasses re-apply the top scene's here.
     */
    protected open fun onPredictiveBackCancelled(top: SceneFragment) {
    }

    /** From SceneFragment.onViewCreated: the revealed scene's hook waits for the commit. */
    @JvmName("dispatchSceneViewCreated")
    internal fun dispatchSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        if (scene === predictBelow) {
            predictBelowViewPending = true
            return
        }
        onSceneViewCreated(scene, savedInstanceState)
    }

    /** From SceneFragment.onDestroyView: skips a revealed view the stage was never told about. */
    @JvmName("dispatchSceneViewDestroyed")
    internal fun dispatchSceneViewDestroyed(scene: SceneFragment) {
        if (scene === predictBelow && predictBelowViewPending) {
            predictBelowViewPending = false
            return
        }
        onSceneViewDestroyed(scene)
    }

    override fun onPause() {
        // The gesture cannot outlive the screen: settle it now.
        endPredictAnimator()
        if (predictTop != null) {
            restoreAfterPredictiveBack()
        }
        super.onPause()
    }

    fun findSceneByTag(tag: String?): SceneFragment? {
        val fragmentManager = supportFragmentManager
        val fragment = fragmentManager.findFragmentByTag(tag)
        return if (fragment != null) {
            fragment as SceneFragment
        } else {
            null
        }
    }

    val topSceneClass: Class<*>?
        get() {
            val index = sceneTagList.size - 1
            if (index < 0) {
                return null
            }
            val tag = sceneTagList[index]
            val fragment = supportFragmentManager.findFragmentByTag(tag) ?: return null
            return fragment.javaClass
        }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(applicationContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    companion object {

        private val TAG = StageActivity::class.java.simpleName

        const val ACTION_START_SCENE = "start_scene"

        const val KEY_SCENE_NAME = "stage_activity_scene_name"
        const val KEY_SCENE_ARGS = "stage_activity_scene_args"

        private const val KEY_STAGE_ID = "stage_activity_stage_id"
        private const val KEY_SCENE_TAG_LIST = "stage_activity_scene_tag_list"
        private const val KEY_NEXT_ID = "stage_activity_next_id"

        /** How far the top scene travels right, as a fraction of the width, while dragged. */
        private const val PREDICT_TOP_SHIFT = 0.2f

        /** Extra travel while it fades out after release; ends where scene_close_exit would. */
        private const val PREDICT_TOP_EXIT_SHIFT = 0.1f

        /** The revealed scene starts this far left, like scene_close_enter. */
        private const val PREDICT_BELOW_SHIFT = 0.1f
        private const val PREDICT_BELOW_MIN_ALPHA = 0.6f
        private const val PREDICT_SETTLE_MIN_MS = 100L
        private const val PREDICT_SETTLE_MAX_MS = 250L

        private val launchModes: MutableMap<Class<*>, Int> = HashMap()

        @JvmStatic
        fun registerLaunchMode(clazz: Class<*>, @SceneFragment.LaunchMode launchMode: Int) {
            if (launchMode != SceneFragment.LAUNCH_MODE_STANDARD &&
                launchMode != SceneFragment.LAUNCH_MODE_SINGLE_TOP &&
                launchMode != SceneFragment.LAUNCH_MODE_SINGLE_TASK
            ) {
                throw IllegalStateException("Invalid launch mode: $launchMode")
            }
            launchModes[clazz] = launchMode
        }

        private fun settleDuration(remaining: Float): Long =
            PREDICT_SETTLE_MIN_MS +
                ((PREDICT_SETTLE_MAX_MS - PREDICT_SETTLE_MIN_MS).toFloat() * max(0f, min(1f, remaining))).toLong()
    }
}
