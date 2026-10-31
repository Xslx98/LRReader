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

package com.lanraragi.framework.scene;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.lanraragi.reader.R;
import com.lanraragi.reader.ui.BaseActivity;
import com.lanraragi.reader.util.AssertUtils;
import com.lanraragi.reader.util.IntIdGenerator;
import com.lanraragi.reader.ui.BackDispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class StageActivity extends BaseActivity {

    private static final String TAG = StageActivity.class.getSimpleName();

    public static final String ACTION_START_SCENE = "start_scene";

    public static final String KEY_SCENE_NAME = "stage_activity_scene_name";
    public static final String KEY_SCENE_ARGS = "stage_activity_scene_args";

    private static final String KEY_STAGE_ID = "stage_activity_stage_id";
    private static final String KEY_SCENE_TAG_LIST = "stage_activity_scene_tag_list";
    private static final String KEY_NEXT_ID = "stage_activity_next_id";

    private final ArrayList<String> mSceneTagList = new ArrayList<>();
    private final ArrayList<String> mDelaySceneTagList = new ArrayList<>();
    private final AtomicInteger mIdGenerator = new AtomicInteger();

    private int mStageId = IntIdGenerator.INVALID_ID;

    // Enabled only while the app has a use for back (see handlesBack()):
    // otherwise the system takes it and shows its back-to-home preview.
    // Subclasses hook in through hasInterceptBack() / onInterceptBack().
    private final OnBackPressedCallback mBackCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
            startPredictiveBack();
        }

        @Override
        public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
            if (mPredictTop != null) {
                applyPredictiveBack(backEvent.getProgress(), 0f);
            }
        }

        @Override
        public void handleOnBackCancelled() {
            cancelPredictiveBack();
        }

        @Override
        public void handleOnBackPressed() {
            if (mPredictTop != null) {
                commitPredictiveBack();
            } else {
                dispatchBack();
            }
        }
    };

    // Predictive back between scenes: the scene being dragged away, the one
    // revealed under it, and the settle animation after the gesture ends.
    @Nullable
    private SceneFragment mPredictTop;
    @Nullable
    private Fragment mPredictBelow;
    private float mPredictProgress;
    private boolean mPredictAttachedBelow;
    private boolean mPredictBelowViewPending;
    @Nullable
    private ValueAnimator mPredictAnimator;

    private final Runnable mUpdateBackCallback = this::updateBackCallback;

    /** Set by findTopScene(true) when it dropped a tag without a scene. */
    private boolean mDroppedStaleTag;

    /** finishScene without the close animation (a predictive back already played one). */
    private boolean mSkipCloseAnimation;

    /** How far the top scene travels right, as a fraction of the width, while dragged. */
    private static final float PREDICT_TOP_SHIFT = 0.2f;
    /** Extra travel while it fades out after release; ends where scene_close_exit would. */
    private static final float PREDICT_TOP_EXIT_SHIFT = 0.1f;
    /** The revealed scene starts this far left, like scene_close_enter. */
    private static final float PREDICT_BELOW_SHIFT = 0.1f;
    private static final float PREDICT_BELOW_MIN_ALPHA = 0.6f;
    private static final long PREDICT_SETTLE_MIN_MS = 100L;
    private static final long PREDICT_SETTLE_MAX_MS = 250L;

    private final SceneViewComparator mSceneViewComparator = new SceneViewComparator();

    private final class SceneViewComparator implements Comparator<View> {

        private int getIndex(View view) {
            Object o = view.getTag(R.id.fragment_tag);
            if (o instanceof String) {
                return mDelaySceneTagList.indexOf(o);
            } else {
                return -1;
            }
        }

        @Override
        public int compare(View lhs, View rhs) {
            return getIndex(lhs) - getIndex(rhs);
        }
    }

    private static final Map<Class<?>, Integer> sLaunchModeMap = new HashMap<>();

    public static void registerLaunchMode(Class<?> clazz, @SceneFragment.LaunchMode int launchMode) {
        if (launchMode != SceneFragment.LAUNCH_MODE_STANDARD &&
                launchMode != SceneFragment.LAUNCH_MODE_SINGLE_TOP &&
                launchMode != SceneFragment.LAUNCH_MODE_SINGLE_TASK) {
            throw new IllegalStateException("Invalid launch mode: " + launchMode);
        }
        sLaunchModeMap.put(clazz, launchMode);
    }

    public abstract int getContainerViewId();

    /**
     * @return {@code true} for start scene
     */
    private boolean startSceneFromIntent(Intent intent) {
        String clazzStr = intent.getStringExtra(KEY_SCENE_NAME);
        if (null == clazzStr) {
            return false;
        }
        // Checked BEFORE Class.forName: the name may come from another app,
        // and loading an arbitrary class runs its static initialisers.
        if (!acceptSceneIntent(intent, clazzStr)) {
            Log.w(TAG, "Rejected start_scene intent");
            return false;
        }

        Class<?> clazz;
        try {
            clazz = Class.forName(clazzStr);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Can't find class " + clazzStr, e);
            return false;
        }

        Bundle args = intent.getBundleExtra(KEY_SCENE_ARGS);

        Announcer announcer = onStartSceneFromIntent(clazz, args);
        if (announcer == null) {
            return false;
        }

        try {
            startScene(announcer);
        } catch (RuntimeException e) {
            // An unstartable scene (e.g. not registered) must never crash
            // the app from an intent.
            Log.e(TAG, "Can't start scene from intent", e);
            return false;
        }
        return true;
    }

    /**
     * Whether a {@code start_scene} intent naming {@code clazzName} may be
     * honoured at all. The intent may come from any app when this activity
     * is exported. Default: accept.
     */
    protected boolean acceptSceneIntent(@NonNull Intent intent, @NonNull String clazzName) {
        return true;
    }

    /**
     * Start scene from {@code Intent}, it might be not safe,
     * Correct it here.
     *
     * @return {@code null} for do not start scene
     */
    @Nullable
    protected Announcer onStartSceneFromIntent(@NonNull Class<?> clazz, @Nullable Bundle args) {
        return new Announcer(clazz).setArgs(args);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null || !ACTION_START_SCENE.equals(intent.getAction()) ||
                !startSceneFromIntent(intent)) {
            onUnrecognizedIntent(intent);
        }
    }

    /**
     * Called when launch with action {@code android.intent.action.MAIN}
     */
    @Nullable
    protected abstract Announcer getLaunchAnnouncer();

    /**
     * Can't recognize intent in first time {@code onCreate} and {@code onNewIntent},
     * null included.
     */
    protected void onUnrecognizedIntent(@Nullable Intent intent) {}

    /**
     * Call {@code setContentView} here. Do <b>NOT</b> call {@code startScene} here
     */
    protected abstract void onCreate2(@Nullable Bundle savedInstanceState);

    @Override
    protected final void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, mBackCallback);

        if (savedInstanceState != null) {
            mStageId = savedInstanceState.getInt(KEY_STAGE_ID, IntIdGenerator.INVALID_ID);
            ArrayList<String> list = savedInstanceState.getStringArrayList(KEY_SCENE_TAG_LIST);
            if (list != null) {
                mSceneTagList.addAll(list);
                mDelaySceneTagList.addAll(list);
            }
            mIdGenerator.lazySet(savedInstanceState.getInt(KEY_NEXT_ID));
        }

        if (mStageId == IntIdGenerator.INVALID_ID) {
            ((SceneApplication) getApplicationContext()).registerStageActivity(this);
        } else {
            ((SceneApplication) getApplicationContext()).registerStageActivity(this, mStageId);
        }

        // Create layout
        onCreate2(savedInstanceState);

        Intent intent = getIntent();
        if (savedInstanceState == null) {
            if (intent != null) {
                String action = intent.getAction();
                if (Intent.ACTION_MAIN.equals(action)) {
                    Announcer announcer = getLaunchAnnouncer();
                    if (announcer != null) {
                        startScene(announcer);
                        return;
                    }
                } else if (ACTION_START_SCENE.equals(action)) {
                    if (startSceneFromIntent(intent)) {
                        return;
                    }
                }
            }

            // Can't recognize intent
            onUnrecognizedIntent(intent);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_STAGE_ID, mStageId);
        outState.putStringArrayList(KEY_SCENE_TAG_LIST, mSceneTagList);
        outState.putInt(KEY_NEXT_ID, mIdGenerator.getAndIncrement());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ((SceneApplication) getApplicationContext()).unregisterStageActivity(mStageId);
    }

    public void onSceneViewCreated(SceneFragment scene, Bundle savedInstanceState) {
    }

    public void onSceneViewDestroyed(SceneFragment scene) {
    }

    void onSceneDestroyed(SceneFragment scene) {
        mDelaySceneTagList.remove(scene.getTag());
    }

    protected void onRegister(int id) {
        mStageId = id;
    }

    protected void onUnregister() {
    }

    protected void onTransactScene() {
    }

    public int getStageId() {
        return mStageId;
    }

    public int getSceneCount() {
        return mSceneTagList.size();
    }

    public int getSceneLaunchMode(Class<?> clazz) {
        Integer integer = sLaunchModeMap.get(clazz);
        if (integer == null) {
            throw new RuntimeException("Not register " + clazz.getName());
        } else {
            return integer;
        }
    }

    private SceneFragment newSceneInstance(Class<?> clazz) {
        return SceneFactory.INSTANCE.create(clazz.getName());
    }

    public void startScene(Announcer announcer) {
        Class<?> clazz = announcer.clazz;
        Bundle args = announcer.args;
        TransitionHelper tranHelper = announcer.tranHelper;
        FragmentManager fragmentManager = getSupportFragmentManager();
        int launchMode = getSceneLaunchMode(clazz);

        // Check LAUNCH_MODE_SINGLE_TASK
        if (launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TASK) {
            for (int i = 0, n = mSceneTagList.size(); i < n; i++) {
                String tag = mSceneTagList.get(i);
                Fragment fragment = fragmentManager.findFragmentByTag(tag);
                if (fragment == null) {
                    Log.e(TAG, "Can't find fragment with tag: " + tag);
                    continue;
                }

                if (clazz.isInstance(fragment)) { // Get it
                    FragmentTransaction transaction = fragmentManager.beginTransaction();

                    // Use default animation
                    transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit);

                    // Remove top fragments
                    for (int j = i + 1; j < n; j++) {
                        String topTag = mSceneTagList.get(j);
                        Fragment topFragment = fragmentManager.findFragmentByTag(topTag);
                        if (null == topFragment) {
                            Log.e(TAG, "Can't find fragment with tag: " + topTag);
                            continue;
                        }
                        // Clear shared element
                        topFragment.setSharedElementEnterTransition(null);
                        topFragment.setSharedElementReturnTransition(null);
                        topFragment.setEnterTransition(null);
                        topFragment.setExitTransition(null);
                        // Remove it
                        transaction.remove(topFragment);
                    }

                    // Remove tag from index i+1
                    mSceneTagList.subList(i + 1, mSceneTagList.size()).clear();

                    // Attach fragment
                    if (fragment.isDetached()) {
                        transaction.attach(fragment);
                    }

                    // Commit
                    transaction.commitAllowingStateLoss();
                    onTransactScene();
                    postUpdateBackCallback();

                    // New arguments
                    if (args != null && fragment instanceof SceneFragment) {
                        ((SceneFragment) fragment).onNewArguments(args);
                    }

                    return;
                }
            }
        }

        // Get current fragment
        SceneFragment currentScene = null;
        if (mSceneTagList.size() > 0) {
            // Get last tag
            String tag = mSceneTagList.get(mSceneTagList.size() - 1);
            Fragment fragment = fragmentManager.findFragmentByTag(tag);
            if (fragment != null) {
                AssertUtils.assertTrue(SceneFragment.class.isInstance(fragment));
                currentScene = (SceneFragment) fragment;
            }
        }

        // Check LAUNCH_MODE_SINGLE_TASK
        if (clazz.isInstance(currentScene) && launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TOP) {
            if (args != null) {
                currentScene.onNewArguments(args);
            }
            return;
        }

        // Create new scene
        SceneFragment newScene = newSceneInstance(clazz);
        newScene.setArguments(args);

        // Create new scene tag
        String newTag = Integer.toString(mIdGenerator.getAndIncrement());

        // Add new tag to list
        mSceneTagList.add(newTag);
        mDelaySceneTagList.add(newTag);

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        // Animation
        if (currentScene != null) {
            if (tranHelper == null || !tranHelper.onTransition(
                    this, transaction, currentScene, newScene)) {
                // Clear shared item
                currentScene.setSharedElementEnterTransition(null);
                currentScene.setSharedElementReturnTransition(null);
                currentScene.setEnterTransition(null);
                currentScene.setExitTransition(null);
                newScene.setSharedElementEnterTransition(null);
                newScene.setSharedElementReturnTransition(null);
                newScene.setEnterTransition(null);
                newScene.setExitTransition(null);
                // Set default animation
                transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit);
            }
            // Detach current scene
            if (!currentScene.isDetached()) {
                transaction.detach(currentScene);
            } else {
                Log.e(TAG, "Current scene is detached");
            }
        }

        // Add new scene
        transaction.add(getContainerViewId(), newScene, newTag);

        // Commit
        transaction.commitAllowingStateLoss();
        onTransactScene();
        postUpdateBackCallback();

        // Check request
        if (announcer.requestFrom != null) {
            newScene.addRequest(announcer.requestFrom.getTag(), announcer.requestCode);
        }
    }

    public void startSceneFirstly(Announcer announcer) {
        Class<?> clazz = announcer.clazz;
        Bundle args = announcer.args;
        FragmentManager fragmentManager = getSupportFragmentManager();
        int launchMode = getSceneLaunchMode(clazz);
        boolean forceNewScene = launchMode == SceneFragment.LAUNCH_MODE_STANDARD;
        boolean createNewScene = true;
        boolean findScene = false;
        SceneFragment scene = null;

        FragmentTransaction transaction = fragmentManager.beginTransaction();

        // Set default animation
        transaction.setCustomAnimations(R.anim.scene_open_enter, R.anim.scene_open_exit);

        String findSceneTag = null;
        for (int i = 0, n = mSceneTagList.size(); i < n; i++) {
            String tag = mSceneTagList.get(i);
            Fragment fragment = fragmentManager.findFragmentByTag(tag);
            if (fragment == null) {
                Log.e(TAG, "Can't find fragment with tag: " + tag);
                continue;
            }

            // Clear shared element
            fragment.setSharedElementEnterTransition(null);
            fragment.setSharedElementReturnTransition(null);
            fragment.setEnterTransition(null);
            fragment.setExitTransition(null);

            // Check is target scene
            if (!forceNewScene && !findScene && clazz.isInstance(fragment) &&
                    (launchMode == SceneFragment.LAUNCH_MODE_SINGLE_TASK || !fragment.isDetached())) {
                scene = (SceneFragment) fragment;
                findScene = true;
                createNewScene = false;
                findSceneTag = tag;
                if (fragment.isDetached()) {
                    transaction.attach(fragment);
                }
            } else {
                // Remove it
                transaction.remove(fragment);
            }
        }

        // Handle tag list
        mSceneTagList.clear();
        if (null != findSceneTag) {
            mSceneTagList.add(findSceneTag);
        }

        if (createNewScene) {
            scene = newSceneInstance(clazz);
            scene.setArguments(args);

            // Create scene tag
            String tag = Integer.toString(mIdGenerator.getAndIncrement());

            // Add tag to list
            mSceneTagList.add(tag);
            mDelaySceneTagList.add(tag);

            // Add scene
            transaction.add(getContainerViewId(), scene, tag);
        }

        // Commit
        transaction.commitAllowingStateLoss();
        onTransactScene();
        postUpdateBackCallback();

        if (!createNewScene && args != null) {
            scene.onNewArguments(args);
        }
    }

    int getSceneIndex(SceneFragment scene) {
        return getTagIndex(scene.getTag());
    }

    int getTagIndex(String tag) {
        return mSceneTagList.indexOf(tag);
    }

    void sortSceneViews(List<View> views) {
        Collections.sort(views, mSceneViewComparator);
    }

    public void finishScene(SceneFragment scene) {
        finishScene(scene, null);
    }

    public void finishScene(SceneFragment scene, TransitionHelper transitionHelper) {
        finishScene(scene.getTag(), transitionHelper);
    }

    private void finishScene(String tag, TransitionHelper transitionHelper) {
        FragmentManager fragmentManager = getSupportFragmentManager();

        // Get scene
        Fragment scene = fragmentManager.findFragmentByTag(tag);
        if (scene == null) {
            Log.e(TAG, "finishScene: Can't find scene by tag: " + tag);
            return;
        }

        // Get scene index
        int index = mSceneTagList.indexOf(tag);
        if (index < 0) {
            Log.e(TAG, "finishScene: Can't find the tag in tag list: " + tag);
            return;
        }

        if (mSceneTagList.size() == 1) {
            // It is the last fragment, finish Activity now
            Log.i(TAG, "finishScene: It is the last scene, finish activity now");
            finish();
            return;
        }

        Fragment next = null;
        if (index == mSceneTagList.size() - 1) {
            // It is first fragment, show the next one
            next = fragmentManager.findFragmentByTag(mSceneTagList.get(index - 1));
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        if (next != null) {
            if (transitionHelper == null || !transitionHelper.onTransition(
                    this, transaction, scene, next)) {
                // Clear shared item
                scene.setSharedElementEnterTransition(null);
                scene.setSharedElementReturnTransition(null);
                scene.setEnterTransition(null);
                scene.setExitTransition(null);
                next.setSharedElementEnterTransition(null);
                next.setSharedElementReturnTransition(null);
                next.setEnterTransition(null);
                next.setExitTransition(null);
                // Do not show animate if it is not the first fragment. A
                // predictive back has already animated both scenes.
                if (!mSkipCloseAnimation) {
                    transaction.setCustomAnimations(R.anim.scene_close_enter, R.anim.scene_close_exit);
                }
            }
            // Attach fragment
            transaction.attach(next);
        }
        transaction.remove(scene);
        transaction.commitAllowingStateLoss();
        onTransactScene();
        postUpdateBackCallback();

        // Remove tag
        mSceneTagList.remove(index);

        // Return result
        if (scene instanceof SceneFragment) {
            ((SceneFragment) scene).returnResult(this);
        }
    }

    public void refreshTopScene() {
        int index = mSceneTagList.size() - 1;
        if (index < 0) {
            return;
        }
        String tag = mSceneTagList.get(index);

        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentByTag(tag);
        if (fragment == null) {
            // Tag list and FragmentManager can diverge after a post-save
            // commitAllowingStateLoss was dropped; detach(null) would NPE.
            Log.e(TAG, "refreshTopScene: Can't find fragment with tag: " + tag);
            return;
        }

        // Two transactions: a detach and an attach in the same one cancel out
        // and the view is never recreated (lint DetachAndAttachSameFragment).
        fragmentManager.beginTransaction().detach(fragment).commitAllowingStateLoss();
        fragmentManager.beginTransaction().attach(fragment).commitAllowingStateLoss();
        onTransactScene();
        postUpdateBackCallback();
    }

    /**
     * Back handling before the scene stack sees it (e.g. closing a drawer).
     *
     * @return {@code true} when consumed
     */
    protected boolean onInterceptBack() {
        return false;
    }

    /** Whether {@link #onInterceptBack()} would consume a back right now. */
    protected boolean hasInterceptBack() {
        return false;
    }

    /**
     * Whether the app has a use for back right now: something to intercept,
     * a scene to pop, or in-scene state on the root scene. When it has none,
     * back belongs to the system (back-to-home on the root task).
     */
    boolean handlesBack() {
        if (hasInterceptBack()) {
            return true;
        }
        if (mSceneTagList.size() > 1) {
            return true;
        }
        SceneFragment top = findTopScene(false);
        return top != null && top.hasInSceneBack();
    }

    /**
     * Re-reads {@link #handlesBack()} into the back callback. Called after
     * every scene transaction, every touch or key release (posted, so click
     * handlers have run) and from {@link SceneFragment#invalidateBack()}.
     */
    public void updateBackCallback() {
        mBackCallback.setEnabled(handlesBack());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean result = super.dispatchTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            postUpdateBackCallback();
        }
        return result;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean result = super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            postUpdateBackCallback();
        }
        return result;
    }

    private void postUpdateBackCallback() {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(mUpdateBackCallback);
        decor.post(mUpdateBackCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBackCallback();
    }

    /**
     * The top scene, or null. With {@code repair} (only from a back event,
     * never from inside a fragment callback), pending transactions run first
     * and a tag whose fragment is still gone — the tag list and the
     * FragmentManager diverged after a dropped post-save commit — is dropped,
     * so back reaches the scene below instead of doing nothing (STAB-05).
     */
    @Nullable
    private SceneFragment findTopScene(boolean repair) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        boolean canRepair = repair && !fragmentManager.isStateSaved();
        if (canRepair) {
            fragmentManager.executePendingTransactions();
        }
        for (int i = mSceneTagList.size() - 1; i >= 0; i--) {
            String tag = mSceneTagList.get(i);
            Fragment fragment = fragmentManager.findFragmentByTag(tag);
            if (fragment instanceof SceneFragment) {
                return (SceneFragment) fragment;
            }
            if (!canRepair) {
                return null;
            }
            Log.e(TAG, "Dropping scene tag without a scene: " + tag);
            mSceneTagList.remove(i);
            mDelaySceneTagList.remove(tag);
            mDroppedStaleTag = true;
        }
        return null;
    }

    /**
     * One back event: {@link #onInterceptBack()}, then the top scene's
     * {@code onBackPressed} — unless the root scene has nothing of its own to
     * do, which hands the back to the system.
     */
    void dispatchBack() {
        if (onInterceptBack()) {
            updateBackCallback();
            return;
        }
        mDroppedStaleTag = false;
        SceneFragment scene = findTopScene(true);
        if (scene != null && mDroppedStaleTag) {
            // The scene this back was meant to leave was already gone, so the
            // screen showed nothing: bring back the one below and stop here.
            if (scene.isDetached()) {
                getSupportFragmentManager().beginTransaction().attach(scene).commitAllowingStateLoss();
            }
            postUpdateBackCallback();
            return;
        }
        if (scene == null || (mSceneTagList.size() == 1 && !scene.hasInSceneBack())) {
            // Nothing of ours to go back to (also the URL-error finish path
            // with an empty stack): the system's back, i.e. home.
            BackDispatch.passThrough(getOnBackPressedDispatcher(), mBackCallback);
            updateBackCallback();
            return;
        }
        scene.onBackPressed();
        updateBackCallback();
    }

    // ======== Predictive back between scenes ========

    /**
     * A back gesture started. When it would simply pop the top scene, attach
     * the scene below now so the gesture can reveal it; anything else (a
     * drawer, in-scene state, the root scene) gets no preview.
     */
    private void startPredictiveBack() {
        endPredictAnimator();
        if (hasInterceptBack() || mSceneTagList.size() < 2) {
            return;
        }
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.isStateSaved()) {
            return;
        }
        fragmentManager.executePendingTransactions();
        SceneFragment top = findTopScene(false);
        if (top == null || top.hasInSceneBack() || top.getView() == null) {
            return;
        }
        String belowTag = mSceneTagList.get(mSceneTagList.size() - 2);
        Fragment below = fragmentManager.findFragmentByTag(belowTag);
        if (below == null) {
            return;
        }
        mPredictTop = top;
        mPredictBelow = below;
        if (below.isDetached()) {
            // Its view is created now but the stage's view hooks (the right
            // drawer belongs to the top scene meanwhile) wait for the commit.
            mPredictAttachedBelow = true;
            fragmentManager.beginTransaction().attach(below).commitNow();
        }
        View topView = top.getView();
        if (topView != null) {
            // Attaching added the revealed view last, i.e. on top.
            topView.bringToFront();
        }
        applyPredictiveBack(0f, 0f);
    }

    /**
     * Places both scenes for gesture {@code progress} (0..1). {@code exit}
     * (0..1) runs after the gesture commits: the top scene fades out and
     * moves on to where the close animation would have left it.
     */
    private void applyPredictiveBack(float progress, float exit) {
        mPredictProgress = progress;
        SceneFragment top = mPredictTop;
        Fragment below = mPredictBelow;
        View topView = top != null ? top.getView() : null;
        View belowView = below != null ? below.getView() : null;
        if (topView != null) {
            float width = topView.getWidth();
            topView.setTranslationX(width * (PREDICT_TOP_SHIFT * progress + PREDICT_TOP_EXIT_SHIFT * exit));
            topView.setAlpha(1f - exit);
        }
        if (belowView != null) {
            float width = belowView.getWidth();
            belowView.setTranslationX(-width * PREDICT_BELOW_SHIFT * (1f - progress));
            belowView.setAlpha(PREDICT_BELOW_MIN_ALPHA + (1f - PREDICT_BELOW_MIN_ALPHA) * progress);
        }
    }

    /** The gesture was released past the threshold: finish the slide, then pop. */
    private void commitPredictiveBack() {
        final SceneFragment top = mPredictTop;
        final float from = mPredictProgress;
        endPredictAnimator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(settleDuration(1f - from));
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            applyPredictiveBack(from + (1f - from) * f, f);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mPredictAnimator = null;
                finishPredictiveBack(top);
            }
        });
        mPredictAnimator = animator;
        animator.start();
    }

    /** Pops {@code top} without the close animation and hands the revealed scene its view hooks. */
    private void finishPredictiveBack(@Nullable SceneFragment top) {
        Fragment below = mPredictBelow;
        boolean belowViewPending = mPredictBelowViewPending;
        resetPredictiveViews();
        mPredictTop = null;
        mPredictBelow = null;
        mPredictAttachedBelow = false;
        mPredictBelowViewPending = false;
        if (top == null || mSceneTagList.isEmpty()
                || !top.getTag().equals(mSceneTagList.get(mSceneTagList.size() - 1))) {
            // The stack moved under the gesture; leave it as it is.
            updateBackCallback();
            return;
        }
        mSkipCloseAnimation = true;
        try {
            finishScene(top);
        } finally {
            mSkipCloseAnimation = false;
        }
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (!fragmentManager.isStateSaved()) {
            fragmentManager.executePendingTransactions();
        }
        if (belowViewPending && below instanceof SceneFragment && below.getView() != null) {
            onSceneViewCreated((SceneFragment) below, null);
        }
    }

    /** The gesture was abandoned: slide back, then hide the revealed scene again. */
    private void cancelPredictiveBack() {
        if (mPredictTop == null) {
            return;
        }
        final float from = mPredictProgress;
        endPredictAnimator();
        ValueAnimator animator = ValueAnimator.ofFloat(from, 0f);
        animator.setDuration(settleDuration(from));
        animator.addUpdateListener(a -> applyPredictiveBack((float) a.getAnimatedValue(), 0f));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mPredictAnimator = null;
                restoreAfterPredictiveBack();
            }
        });
        mPredictAnimator = animator;
        animator.start();
    }

    private void restoreAfterPredictiveBack() {
        SceneFragment top = mPredictTop;
        Fragment below = mPredictBelow;
        resetPredictiveViews();
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (below != null && mPredictAttachedBelow && !below.isDetached()
                && !fragmentManager.isStateSaved()) {
            // Still mPredictBelow here, so its view-destroyed hook is skipped too.
            fragmentManager.beginTransaction().detach(below).commitNow();
        }
        mPredictTop = null;
        mPredictBelow = null;
        mPredictAttachedBelow = false;
        mPredictBelowViewPending = false;
        if (top != null) {
            onPredictiveBackCancelled(top);
        }
        updateBackCallback();
    }

    /** Runs a pending settle to its end at once (a new gesture, or the stage going away). */
    private void endPredictAnimator() {
        ValueAnimator animator = mPredictAnimator;
        if (animator != null) {
            animator.end();
        }
    }

    private void resetPredictiveViews() {
        for (Fragment fragment : new Fragment[]{mPredictTop, mPredictBelow}) {
            View view = fragment != null ? fragment.getView() : null;
            if (view != null) {
                view.setTranslationX(0f);
                view.setAlpha(1f);
            }
        }
    }

    private static long settleDuration(float remaining) {
        return PREDICT_SETTLE_MIN_MS
                + (long) ((PREDICT_SETTLE_MAX_MS - PREDICT_SETTLE_MIN_MS) * Math.max(0f, Math.min(1f, remaining)));
    }

    /**
     * A predictive back was abandoned and {@code top} stays. The revealed
     * scene's own view setup (drawer lock, checked nav item) ran meanwhile;
     * subclasses re-apply the top scene's here.
     */
    protected void onPredictiveBackCancelled(@NonNull SceneFragment top) {
    }

    /** From SceneFragment.onViewCreated: the revealed scene's hook waits for the commit. */
    void dispatchSceneViewCreated(SceneFragment scene, @Nullable Bundle savedInstanceState) {
        if (scene == mPredictBelow) {
            mPredictBelowViewPending = true;
            return;
        }
        onSceneViewCreated(scene, savedInstanceState);
    }

    /** From SceneFragment.onDestroyView: skips a revealed view the stage was never told about. */
    void dispatchSceneViewDestroyed(SceneFragment scene) {
        if (scene == mPredictBelow && mPredictBelowViewPending) {
            mPredictBelowViewPending = false;
            return;
        }
        onSceneViewDestroyed(scene);
    }

    @Override
    protected void onPause() {
        // The gesture cannot outlive the screen: settle it now.
        endPredictAnimator();
        if (mPredictTop != null) {
            restoreAfterPredictiveBack();
        }
        super.onPause();
    }

    public SceneFragment findSceneByTag(String tag) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentByTag(tag);
        if (fragment != null) {
            return (SceneFragment) fragment;
        } else {
            return null;
        }
    }

    @Nullable
    public Class<?> getTopSceneClass() {
        int index = mSceneTagList.size() - 1;
        if (index < 0) {
            return null;
        }
        String tag = mSceneTagList.get(index);
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (null == fragment) {
            return null;
        }
        return fragment.getClass();
    }

    @Override
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {
        if (overrideConfiguration != null){
            int uiMode = overrideConfiguration.uiMode;
            overrideConfiguration.setTo(getApplicationContext().getResources().getConfiguration());
            overrideConfiguration.uiMode = uiMode;
        }
        super.applyOverrideConfiguration(overrideConfiguration);
    }
}
