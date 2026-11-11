package com.lanraragi.reader.ui.reader

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.reading.NextArchiveResolver
import com.lanraragi.reader.reading.ReadingContext
import com.lanraragi.reader.reading.ReadingContextStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/**
 * Issue #110: while the end-of-book panel shows, a page key or the mouse
 * wheel closes it, as Back does, instead of turning the page behind it, the
 * first key after a touch is not spent on moving focus, and DPAD_UP/DOWN
 * reach the panel's buttons. Events go through the window's real input
 * pipeline, which is where leaving touch mode used to eat that key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ReaderContinuationKeyTest {

    /** Stands in for GalleryActivity: its key path, the panel over a page. */
    class Host : AppCompatActivity() {
        lateinit var continuation: ReaderContinuationController
        lateinit var page: View
        private val input = GalleryInputHandler(object : GalleryInputHandler.Callback {
            override fun onTapMenuArea() {}
        })
        var pageTurns = 0
        val launched = mutableListOf<String>()

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppTheme_Gallery_Dark)
            super.onCreate(savedInstanceState)
            val root = FrameLayout(this)
            page = View(this)
            // The GL view's wheel listener: the mouse wheel turns pages.
            page.setOnGenericMotionListener { _, event ->
                val wheel = event.actionMasked == MotionEvent.ACTION_SCROLL
                if (wheel) pageTurns++
                wheel
            }
            root.addView(page)
            layoutInflater.inflate(R.layout.reader_continuation_panel, root, true)
            setContentView(root)
            continuation = ReaderContinuationController(
                root = root.findViewById(R.id.continuation_scrim),
                scope = lifecycleScope,
                resolver = NextArchiveResolver(OkHttpClient()),
                launchNext = { launched += it.arcid },
            )
            continuation.setCurrentArchive(ARCID)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (continuation.onKeyDown(keyCode, event, input.isReaderKey(keyCode))) return true
            if (input.isReaderKey(keyCode)) {
                pageTurns++
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    private lateinit var host: Host

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        // The only archive in the list: the panel says it is the end.
        ReadingContextStore.publish(
            ReadingContext.LocalList(ReadingContext.LocalList.Kind.DOWNLOADS, listOf(archive()), ARCID),
        )
        // The panel debounces events less than half a second after boot.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        host = Robolectric.buildActivity(Host::class.java).setup().visible().get()
        // Keys reach only a window that has the input focus.
        ReflectionHelpers.callInstanceMethod<Unit>(
            viewRoot(), "windowFocusChanged",
            ClassParameter.from(Boolean::class.javaPrimitiveType, true),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        idle()
    }

    @After
    fun tearDown() {
        ReadingContextStore.clear()
    }

    @Test
    fun `the first page key after a touch closes the panel without turning the page`() {
        touchThePage()
        assertTrue("precondition: a touch put the window in touch mode", host.page.isInTouchMode)
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        assertTrue("precondition: the panel shows", host.continuation.isShowing)

        key(KeyEvent.KEYCODE_PAGE_DOWN)

        assertFalse("the first key closed the panel", host.continuation.isShowing)
        assertEquals("no page turned behind the panel", 0, host.pageTurns)

        key(KeyEvent.KEYCODE_PAGE_DOWN)
        assertEquals("with the panel closed, keys turn pages again", 1, host.pageTurns)
    }

    @Test
    fun `an arrow key closes the panel shown while keys were in use`() {
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, host.pageTurns)
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        assertTrue("precondition: the panel shows", host.continuation.isShowing)

        key(KeyEvent.KEYCODE_DPAD_LEFT)

        assertFalse("the key closed the panel", host.continuation.isShowing)
        assertEquals("no page turned behind the panel", 1, host.pageTurns)
    }

    @Test
    fun `the first tap on the scrim closes the panel after a key focused a button`() {
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        // Where Tab moves the focus from the scrim.
        val back = host.findViewById<Button>(R.id.continuation_secondary)
        assertTrue("precondition: the button takes the focus", back.requestFocus())
        assertFalse("precondition: keys are in use", back.isInTouchMode)

        touch(SCRIM_X, SCRIM_Y)

        assertFalse("the first tap closed the panel", host.continuation.isShowing)
    }

    @Test
    fun `the mouse wheel closes the panel without turning the page`() {
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        assertTrue("precondition: the panel shows", host.continuation.isShowing)

        wheel()

        assertFalse("the wheel closed the panel", host.continuation.isShowing)
        assertEquals("no page turned behind the panel", 0, host.pageTurns)

        wheel()
        assertEquals("with the panel closed, the wheel turns pages again", 1, host.pageTurns)
    }

    @Test
    fun `DPAD_DOWN focuses Read next and Enter on it opens the next archive`() {
        publishNextArchive()
        touchThePage()
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        val readNext = host.findViewById<Button>(R.id.continuation_primary)
        val back = host.findViewById<Button>(R.id.continuation_secondary)
        assertEquals("precondition: the panel offers the next archive", View.VISIBLE, readNext.visibility)

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("the first DPAD_DOWN after a touch focused Read next", readNext.isFocused)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("DPAD_DOWN moved on to Back", back.isFocused)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("DPAD_UP moved back to Read next", readNext.isFocused)
        assertTrue("moving the focus kept the panel open", host.continuation.isShowing)

        key(KeyEvent.KEYCODE_ENTER)

        assertEquals("Enter opened the next archive", listOf(NEXT_ARCID), host.launched)
        assertEquals("no page turned behind the panel", 0, host.pageTurns)
    }

    @Test
    fun `DPAD_LEFT on a focused button closes the panel without turning the page`() {
        publishNextArchive()
        host.continuation.onEndOfBookEvent(atLastPage = true)
        idle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(
            "precondition: Read next has the focus",
            host.findViewById<Button>(R.id.continuation_primary).isFocused,
        )

        key(KeyEvent.KEYCODE_DPAD_LEFT)

        assertFalse("the page key closed the panel", host.continuation.isShowing)
        assertEquals("no page turned behind the panel", 0, host.pageTurns)
        assertTrue("the button did not act on the key", host.launched.isEmpty())
    }

    /** Two archives in the list: the panel offers the second one. */
    private fun publishNextArchive() {
        ReadingContextStore.publish(
            ReadingContext.LocalList(
                ReadingContext.LocalList.Kind.DOWNLOADS,
                listOf(archive(), archive(NEXT_ARCID)),
                ARCID,
            ),
        )
    }

    private fun touchThePage() = touch(SCRIM_X, SCRIM_Y)

    /** One notch of a mouse wheel over a corner of the screen. */
    private fun wheel() {
        val now = SystemClock.uptimeMillis()
        val coords = MotionEvent.PointerCoords().apply {
            x = SCRIM_X
            y = SCRIM_Y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -1f)
        }
        val props = MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }
        dispatch(
            MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
            ),
        )
    }

    private fun touch(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            dispatch(event)
        }
    }

    private fun key(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            dispatch(
                KeyEvent(now, now, action, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD),
            )
        }
    }

    /** Through ViewRootImpl's input stages, as the system delivers events. */
    private fun dispatch(event: InputEvent) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            viewRoot(), "dispatchInputEvent", ClassParameter.from(InputEvent::class.java, event),
        )
        idle()
    }

    private fun viewRoot(): Any = ReflectionHelpers.callInstanceMethod(host.window.decorView, "getViewRootImpl")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun archive(arcid: String = ARCID) = Archive(
        arcid = arcid, title = "t", tags = emptyMap(), pagecount = 3, progress = 0,
        extension = "zip", filename = "f", thumbnailUrl = "", rating = 0f,
        isnew = false, lastreadtime = 0L, summary = null, serverProfileId = 3L,
    )

    private companion object {
        // A corner: outside the centred card, on the page or the scrim.
        const val SCRIM_X = 10f
        const val SCRIM_Y = 10f
        const val ARCID = "0123456789abcdef0123456789abcdef01234567"
        const val NEXT_ARCID = "1123456789abcdef0123456789abcdef01234567"
    }
}
