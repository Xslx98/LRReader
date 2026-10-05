package com.lanraragi.framework.scene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

        override fun getContainerViewId(): Int = CONTAINER_ID

        override fun getLaunchAnnouncer(): Announcer? = null

        override fun onCreate2(savedInstanceState: Bundle?) {
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }

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
        repeat(count) { stage.startScene(Announcer(CountingScene::class.java)) }
        ShadowLooper.idleMainLooper()
        return stage
    }

    private fun TestStage.scenes(): List<CountingScene> =
        supportFragmentManager.fragments.filterIsInstance<CountingScene>()

    private fun TestStage.topScene(): CountingScene = scenes().last()

    @Test
    fun backGoesToTheTopSceneOnly() {
        val stage = stageWithScenes(2)
        assertEquals(2, stage.scenes().size)
        val bottom = stage.scenes().first()

        stage.onBackPressedDispatcher.onBackPressed()

        assertEquals(1, stage.topScene().backs)
        assertEquals(0, bottom.backs)
        assertFalse(stage.isFinishing)
    }

    @Test
    fun anInterceptedBackNeverReachesTheScene() {
        val stage = stageWithScenes(1)
        stage.intercept = true

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

    private companion object {
        const val CONTAINER_ID = 0x7f0fbac1
    }
}
