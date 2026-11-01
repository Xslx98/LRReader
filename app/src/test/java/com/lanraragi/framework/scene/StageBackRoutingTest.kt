package com.lanraragi.framework.scene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Back reaches the scene stack through OnBackPressedDispatcher (audit C15): with
 * predictive back (targetSdk 36) an overridden Activity.onBackPressed is never called,
 * so a regression here makes the back gesture finish the app from any scene.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StageBackRoutingTest.TestApp::class)
class StageBackRoutingTest {

    class TestApp : SceneApplication()

    class CountingScene : SceneFragment() {
        var backs = 0
        var inSceneBack = false

        override fun hasInSceneBack(): Boolean = inSceneBack

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onBackPressed() {
            backs++
        }
    }

    class TestStage : StageActivity() {
        var intercept = false
        var intercepted = 0

        override fun getThemeResId(theme: Int): Int = R.style.AppTheme_Main

        override val containerViewId: Int get() = CONTAINER_ID

        override fun getLaunchAnnouncer(): Announcer? = null

        override fun onCreate2(savedInstanceState: Bundle?) {
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }

        override fun hasInterceptBack(): Boolean = intercept

        override fun onInterceptBack(): Boolean {
            if (intercept) intercepted++
            return intercept
        }
    }

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        StageActivity.registerLaunchMode(CountingScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
        SceneFactory.register(CountingScene::class.java.name) { CountingScene() }
    }

    private fun stageWithScenes(count: Int): TestStage {
        val stage = Robolectric.buildActivity(TestStage::class.java).setup().get()
        repeat(count) {
            stage.startScene(Announcer(CountingScene::class.java))
            ShadowLooper.idleMainLooper()
        }
        return stage
    }

    private fun TestStage.scenes(): List<CountingScene> =
        supportFragmentManager.fragments.filterIsInstance<CountingScene>()

    private fun TestStage.topScene(): CountingScene = scenes().last()

    @Test
    fun backGoesToTheTopSceneOnly() {
        val stage = stageWithScenes(2)
        assertEquals(2, stage.sceneCount)
        val bottom = stage.findSceneByTag("0") as CountingScene

        stage.onBackPressedDispatcher.onBackPressed()

        assertEquals(1, stage.topScene().backs)
        assertEquals(0, bottom.backs)
        assertFalse(stage.isFinishing)
    }

    @Test
    fun anInterceptedBackNeverReachesTheScene() {
        val stage = stageWithScenes(1)
        stage.intercept = true
        stage.updateBackCallback()

        stage.onBackPressedDispatcher.onBackPressed()

        assertEquals(1, stage.intercepted)
        assertEquals(0, stage.topScene().backs)
        assertFalse(stage.isFinishing)
    }

    @Test
    fun anEmptyStackFallsBackToTheDefaultFinish() {
        val stage = stageWithScenes(0)

        stage.onBackPressedDispatcher.onBackPressed()

        assertTrue(stage.isFinishing)
    }

    @Test
    fun aRootSceneWithNothingToDoLeavesBackToTheSystem() {
        val stage = stageWithScenes(1)

        assertFalse(stage.onBackPressedDispatcher.hasEnabledCallbacks())
        stage.onBackPressedDispatcher.onBackPressed()

        assertEquals(0, stage.topScene().backs)
        assertTrue(stage.isFinishing)
    }

    @Test
    fun aRootSceneWithInSceneStateKeepsBack() {
        val stage = stageWithScenes(1)
        stage.topScene().inSceneBack = true
        stage.topScene().invalidateBack()

        stage.onBackPressedDispatcher.onBackPressed()

        assertEquals(1, stage.topScene().backs)
        assertFalse(stage.isFinishing)
    }

    @Test
    fun aTagWithoutItsSceneIsSkipped() {
        val stage = stageWithScenes(2)
        val bottom = stage.findSceneByTag("0") as CountingScene
        val top = stage.findSceneByTag("1") as CountingScene
        // The FragmentManager lost the top scene behind the tag list's back (STAB-05).
        stage.supportFragmentManager.beginTransaction().remove(top).commitNow()

        stage.onBackPressedDispatcher.onBackPressed()

        ShadowLooper.idleMainLooper()
        // That back re-shows the scene below instead of leaving it.
        assertEquals(0, bottom.backs)
        assertEquals(1, stage.sceneCount)
        assertFalse(bottom.isDetached)
        assertFalse(stage.isFinishing)
    }

    private fun gesture(progress: Float) = BackEventCompat(0f, 500f, progress, BackEventCompat.EDGE_LEFT)

    private fun settle() = ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)

    @Test
    fun aCommittedBackGesturePopsTheTopSceneWithoutCallingItsBack() {
        val stage = stageWithScenes(2)
        val bottom = stage.findSceneByTag("0") as CountingScene
        val top = stage.findSceneByTag("1") as CountingScene
        assertTrue(bottom.isDetached)
        val dispatcher = stage.onBackPressedDispatcher

        dispatcher.dispatchOnBackStarted(gesture(0f))
        // The scene below is revealed for the gesture.
        assertFalse(bottom.isDetached)
        dispatcher.dispatchOnBackProgressed(gesture(0.6f))
        dispatcher.onBackPressed()
        settle()

        assertEquals(1, stage.sceneCount)
        assertEquals(0, top.backs)
        assertFalse(bottom.isDetached)
        assertEquals(1f, bottom.requireView().alpha, 0.001f)
        assertEquals(0f, bottom.requireView().translationX, 0.001f)
    }

    @Test
    fun aCancelledBackGestureKeepsTheStack() {
        val stage = stageWithScenes(2)
        val bottom = stage.findSceneByTag("0") as CountingScene
        val top = stage.findSceneByTag("1") as CountingScene
        val dispatcher = stage.onBackPressedDispatcher

        dispatcher.dispatchOnBackStarted(gesture(0f))
        dispatcher.dispatchOnBackProgressed(gesture(0.4f))
        dispatcher.dispatchOnBackCancelled()
        settle()

        assertEquals(2, stage.sceneCount)
        assertTrue(bottom.isDetached)
        assertEquals(0, top.backs)
        assertEquals(1f, top.requireView().alpha, 0.001f)
        assertEquals(0f, top.requireView().translationX, 0.001f)
    }

    @Test
    fun inSceneStateGetsNoPreviewAndReachesTheScene() {
        val stage = stageWithScenes(2)
        val bottom = stage.findSceneByTag("0") as CountingScene
        val top = stage.findSceneByTag("1") as CountingScene
        top.inSceneBack = true
        val dispatcher = stage.onBackPressedDispatcher

        dispatcher.dispatchOnBackStarted(gesture(0f))
        assertTrue(bottom.isDetached)
        dispatcher.onBackPressed()
        settle()

        assertEquals(1, top.backs)
        assertEquals(2, stage.sceneCount)
    }

    private companion object {
        const val CONTAINER_ID = 0x7f0fbac1
    }
}
