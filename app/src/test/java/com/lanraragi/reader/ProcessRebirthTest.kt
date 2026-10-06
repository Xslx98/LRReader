package com.lanraragi.reader

import android.app.AlarmManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.w3c.dom.Element
import java.io.File
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Audit 2026-10-06b STAB-10: `LRReaderApplication.restart()` scheduled an
 * inexact alarm and killed the process; on API 34+ the alarm's activity start
 * from the dead app is blocked, so the app never came back. The rebirth now
 * goes through [PhoenixActivity] in its own process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProcessRebirthTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowProcess.setPid(MAIN_PID)
    }

    private fun registerLauncher() {
        val component = ComponentName(ctx.packageName, "com.lanraragi.reader.ui.MainActivity")
        val pm = shadowOf(ctx.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun triggerStartsThePhoenixWithThePidAndAFreshTaskLaunchIntent() {
        registerLauncher()

        ProcessRebirth.trigger(ctx)

        val started = shadowOf(ctx).nextStartedActivity
        assertNotNull("restart must start the phoenix activity", started)
        assertEquals(PhoenixActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(MAIN_PID, started.getIntExtra(ProcessRebirth.EXTRA_MAIN_PID, -1))
        val launch = IntentCompat.getParcelableExtra(started, ProcessRebirth.EXTRA_LAUNCH_INTENT, Intent::class.java)
        assertNotNull(launch)
        assertEquals(ctx.packageName, launch!!.component?.packageName)
        val clear = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(clear, launch.flags and clear)

        // No alarm any more: it was the path that failed on API 34+.
        val alarms = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertTrue(alarms.scheduledAlarms.isEmpty())

        // The phoenix kills this process; the self-kill is only a delayed safety net.
        assertFalse(ShadowProcess.wasKilled(MAIN_PID))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ProcessRebirth.FALLBACK_KILL_DELAY_MS))
        assertTrue(ShadowProcess.wasKilled(MAIN_PID))
    }

    @Test
    fun whenThePhoenixCannotStartTheProcessIsStillKilledAtOnce() {
        registerLauncher()
        val refusing = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("test")
            }
        }

        ProcessRebirth.trigger(refusing)

        assertTrue(ShadowProcess.wasKilled(MAIN_PID))
    }

    @Test
    fun onlyThePhoenixProcessNameMatches() {
        assertTrue(ProcessRebirth.isPhoenixProcess("com.lanraragi.reader:phoenix"))
        assertTrue(ProcessRebirth.isPhoenixProcess("com.lanraragi.reader.debug:phoenix"))
        assertFalse(ProcessRebirth.isPhoenixProcess("com.lanraragi.reader"))
        assertFalse(ProcessRebirth.isPhoenixProcess(null))
    }

    @Test
    fun phoenixKillsTheMainProcessAndStartsTheLaunchIntent() {
        val launch = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(ctx.packageName, "com.lanraragi.reader.ui.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ShadowProcess.setPid(PHOENIX_PID)

        val controller = Robolectric.buildActivity(
            PhoenixActivity::class.java,
            ProcessRebirth.phoenixIntent(ctx, launch, MAIN_PID),
        ).create()
        val activity = controller.get()

        assertTrue("the old main process must be killed", ShadowProcess.wasKilled(MAIN_PID))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the app must be relaunched", started)
        assertEquals(launch.component, started.component)
        val clear = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(clear, started.flags and clear)
        assertTrue(activity.isFinishing)
        assertTrue("the phoenix process exits", ShadowProcess.wasKilled(PHOENIX_PID))
    }

    @Test
    fun phoenixIgnoresAForeignLaunchIntentAndItsOwnPid() {
        val foreign = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.example.other", "com.example.other.Main"))
        ShadowProcess.setPid(PHOENIX_PID)

        val activity = Robolectric.buildActivity(
            PhoenixActivity::class.java,
            ProcessRebirth.phoenixIntent(ctx, foreign, MAIN_PID),
        ).get()
        ShadowProcess.clearKilledProcesses()
        activity.relaunch(ProcessRebirth.phoenixIntent(ctx, foreign, PHOENIX_PID))

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ShadowProcess.wasKilled(PHOENIX_PID))
    }

    @Test
    fun manifestDeclaresThePhoenixNonExportedInItsOwnProcess() {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("run from the app module dir: ${file.absolutePath}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val activities = doc.getElementsByTagName("activity")
        val phoenix = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == PhoenixActivity::class.java.name }
        assertEquals("false", phoenix.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals(ProcessRebirth.PROCESS_SUFFIX, phoenix.getAttributeNS(ANDROID_NS, "process"))
        assertEquals("true", phoenix.getAttributeNS(ANDROID_NS, "excludeFromRecents"))
    }

    private companion object {
        const val MAIN_PID = 1234
        const val PHOENIX_PID = 5678
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
