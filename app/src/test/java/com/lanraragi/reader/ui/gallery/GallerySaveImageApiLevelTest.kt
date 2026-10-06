package com.lanraragi.reader.ui.gallery

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.gallery.GalleryProvider2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Audit 2026-10-06d STAB-11: "Save" inserted into the MediaStore volume
 * `external_primary` (API 29, baselined as InlinedApi) with RELATIVE_PATH
 * (API 29) and no WRITE_EXTERNAL_STORAGE, so on Android 9 it always failed.
 * There it now opens the "Save to" picker; from API 29 it keeps the
 * MediaStore save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GallerySaveImageApiLevelTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private val launcher = RecordingLauncher()

    @Before
    fun setUp() {
        ShadowToast.reset()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.close()
    }

    @Test(timeout = 20_000)
    @Config(sdk = [28])
    fun android9_saveOpensThePicker() {
        operations().saveImage(0)

        awaitUntil(message = "picker not launched") { launcher.launched.isNotEmpty() }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launcher.launched.single().action)
    }

    @Test(timeout = 20_000)
    @Config(sdk = [29])
    fun android10_saveUsesTheMediaStore() {
        operations().saveImage(0)

        // The MediaStore path always ends with a toast (saved or failed).
        awaitUntil(message = "save did not finish") { ShadowToast.getLatestToast() != null }
        assertTrue("no picker from API 29", launcher.launched.isEmpty())
    }

    private fun operations() = GalleryImageOperations(activity).apply {
        galleryProvider = OnePageProvider()
        saveToLauncher = launcher
    }

    private class RecordingLauncher : ActivityResultLauncher<Intent>() {
        val launched = java.util.concurrent.CopyOnWriteArrayList<Intent>()

        override fun launch(input: Intent, options: ActivityOptionsCompat?) {
            launched.add(input)
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<Intent, *>
            get() = ActivityResultContracts.StartActivityForResult()
    }

    /** One page whose save writes a small file. */
    private class OnePageProvider : GalleryProvider2() {
        override fun size(): Int = 1
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String = ""
        override fun getImageFilename(index: Int): String = "page_$index"
        override fun save(index: Int, file: UniFile): Boolean = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
            val file = dir.createFile("$filename.jpg") ?: return null
            file.openOutputStream().use { it.write(byteArrayOf(1, 2, 3)) }
            return file
        }
    }
}
