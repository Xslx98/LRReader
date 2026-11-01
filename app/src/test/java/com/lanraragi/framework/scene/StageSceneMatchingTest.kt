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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Launch modes match the announced scene class EXACTLY: a subclass scene
 * (the gallery's tank mode is a GalleryListScene subclass) must never be
 * reused, re-argued or returned to when its parent class is started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StageSceneMatchingTest.TestApp::class)
class StageSceneMatchingTest {

    class TestApp : SceneApplication()

    open class ParentScene : SceneFragment() {
        var newArguments = 0

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onNewArguments(args: Bundle) {
            newArguments++
        }
    }

    class ChildScene : ParentScene()

    class TestStage : StageActivity() {
        override fun getThemeResId(theme: Int): Int = R.style.AppTheme_Main

        override val containerViewId: Int get() = STAGE_CONTAINER_ID

        override fun getLaunchAnnouncer(): Announcer? = null

        override fun onCreate2(savedInstanceState: Bundle?) {
            setContentView(FrameLayout(this).apply { id = STAGE_CONTAINER_ID })
        }
    }

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        StageActivity.registerLaunchMode(ParentScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
        StageActivity.registerLaunchMode(ChildScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        SceneFactory.register(ParentScene::class.java.name) { ParentScene() }
        SceneFactory.register(ChildScene::class.java.name) { ChildScene() }
    }

    private fun TestStage.start(clazz: Class<*>, firstly: Boolean = false) {
        val announcer = Announcer(clazz).setArgs(Bundle())
        if (firstly) startSceneFirstly(announcer) else startScene(announcer)
        ShadowLooper.idleMainLooper()
    }

    /** Stack order: tags only grow, and lower scenes are detached (absent from `fragments`). */
    private fun TestStage.sceneClasses(): List<Class<*>> =
        (0 until MAX_TAG).mapNotNull { findSceneByTag(it.toString())?.javaClass }

    private fun stage(): TestStage = Robolectric.buildActivity(TestStage::class.java).setup().get()

    @Test
    fun singleTopParentOverASubclassStartsANewParent() {
        val stage = stage()
        stage.start(ParentScene::class.java)
        stage.start(ChildScene::class.java)
        val child = stage.findSceneByTag("1") as ChildScene

        stage.start(ParentScene::class.java)

        assertEquals(0, child.newArguments)
        assertEquals(
            listOf(ParentScene::class.java, ChildScene::class.java, ParentScene::class.java),
            stage.sceneClasses(),
        )
    }

    @Test
    fun startSceneFirstlyReusesTheParentAndDropsTheSubclass() {
        val stage = stage()
        stage.start(ParentScene::class.java)
        stage.start(ChildScene::class.java)

        stage.start(ParentScene::class.java, firstly = true)

        assertEquals(listOf<Class<*>>(ParentScene::class.java), stage.sceneClasses())
    }

    @Test
    fun singleTaskSubclassReturnsOnlyToItself() {
        val stage = stage()
        stage.start(ParentScene::class.java)
        stage.start(ChildScene::class.java)
        stage.start(ParentScene::class.java)

        stage.start(ChildScene::class.java)

        assertEquals(listOf(ParentScene::class.java, ChildScene::class.java), stage.sceneClasses())
    }
}

private const val STAGE_CONTAINER_ID = 0x7f0fbac2
private const val MAX_TAG = 16
