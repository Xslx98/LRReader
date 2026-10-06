package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.scene.BaseScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06 N4: the archive DELETE runs on the app scope and its Main
 * callback only checked that the activity was alive. Leaving the detail scene
 * during a slow DELETE made the callback call scene.getString on a removed
 * fragment -> IllegalStateException ("not attached to a context") on Main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DeleteSuccessDeliveryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Public: FragmentManager.add requires a recreatable fragment class. */
    class RecordingScene : BaseScene() {
        val tips = mutableListOf<CharSequence>()
        var backPressed = 0

        override fun showTip(message: CharSequence, length: Int) {
            tips += message
        }

        override fun onBackPressed() {
            backPressed++
        }
    }

    @Test
    fun `a scene that was left reports through the host and is not finished`() {
        val scene = RecordingScene() // never added, as after popping it
        val hostTips = mutableListOf<CharSequence>()

        DetailActionHandler.deliverDeleteSuccess(scene, "Book", context) { hostTips += it }

        assertEquals(listOf(context.getString(R.string.lrr_delete_success, "Book")), hostTips)
        assertTrue(scene.tips.isEmpty())
        assertEquals(0, scene.backPressed)
    }

    @Test
    fun `a scene still shown gets the tip and closes itself`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val scene = RecordingScene()
        activity.supportFragmentManager.beginTransaction().add(scene, "detail").commitNow()
        val hostTips = mutableListOf<CharSequence>()

        DetailActionHandler.deliverDeleteSuccess(scene, "Book", context) { hostTips += it }

        assertEquals(listOf(context.getString(R.string.lrr_delete_success, "Book")), scene.tips)
        assertEquals(1, scene.backPressed)
        assertTrue(hostTips.isEmpty())
    }
}
