package com.lanraragi.framework.scene

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.FrameLayout
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The host app applies its Activity-wide policies (window flags, theme, app lock)
 * through StageActivity's hooks, which must run exactly where a base class between
 * AppCompatActivity and StageActivity would: around the platform callback and before
 * the stage's own work. `Activity.onCreate/onResume/onPause` dispatch the
 * application's lifecycle callbacks, which mark the platform call in the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StageLifecycleHooksTest.TestApp::class)
class StageLifecycleHooksTest {

    class TestApp : SceneApplication()

    class TestStage : StageActivity() {
        val log = ArrayList<String>()

        override val containerViewId: Int get() = CONTAINER_ID

        override fun getLaunchAnnouncer(): Announcer? = null

        override fun onStageBeforeCreate(savedInstanceState: Bundle?) {
            log += "beforeCreate"
            setTheme(R.style.AppTheme_Main)
        }

        override fun onStageCreated(savedInstanceState: Bundle?) {
            log += "created"
        }

        override fun onCreate2(savedInstanceState: Bundle?) {
            log += "onCreate2"
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }

        override fun onStageResumed() {
            log += "resumed"
        }

        override fun onStagePausing() {
            log += "pausing"
        }
    }

    private fun recordPlatformCallbacks(): Application.ActivityLifecycleCallbacks {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as TestStage).log += "platform.onCreate"
            }

            override fun onActivityResumed(activity: Activity) {
                (activity as TestStage).log += "platform.onResume"
            }

            override fun onActivityPaused(activity: Activity) {
                (activity as TestStage).log += "platform.onPause"
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(callbacks)
        return callbacks
    }

    @Test
    fun hooksWrapThePlatformCallbacksBeforeTheStageWork() {
        recordPlatformCallbacks()
        val controller = Robolectric.buildActivity(TestStage::class.java).setup()

        controller.pause()

        assertEquals(
            listOf(
                "beforeCreate", "platform.onCreate", "created", "onCreate2",
                "platform.onResume", "resumed",
                "pausing", "platform.onPause",
            ),
            controller.get().log,
        )
    }

    private companion object {
        const val CONTAINER_ID = 0x7f0c0001
    }
}
