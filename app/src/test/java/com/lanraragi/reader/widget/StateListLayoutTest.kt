package com.lanraragi.reader.widget

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.list.ListScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [StateListLayout.render]: one slot per state, the message slot worded for empty vs failed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class StateListLayoutTest {

    private lateinit var layout: StateListLayout
    private lateinit var progress: View
    private lateinit var content: View
    private lateinit var message: View
    private var retries = 0

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        layout = StateListLayout(context).apply {
            emptyText = "Nothing here"
            onRetry = { retries++ }
        }
        progress = View(context).apply { id = R.id.state_progress }
        content = View(context).apply { id = R.id.state_content }
        message = LayoutInflater.from(context).inflate(R.layout.view_error_state, layout, false).apply {
            id = R.id.state_message
        }
        val toolbar = View(context)
        listOf(progress, content, message, toolbar).forEach {
            layout.addView(it, FrameLayout.LayoutParams(1, 1))
        }
    }

    private fun shown(): List<String> = buildList {
        if (progress.visibility == View.VISIBLE) add("progress")
        if (message.visibility == View.VISIBLE) add("message")
        if (content.visibility == View.VISIBLE) add("content")
    }

    private fun text(id: Int): String? = message.findViewById<TextView>(id).takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    private fun visible(id: Int): Boolean = message.findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun `loading shows only the progress slot`() {
        layout.render(ListScreenState.Loading)
        assertEquals(listOf("progress"), shown())
    }

    @Test
    fun `items show only the content slot, also when a reload failed`() {
        layout.render(ListScreenState.Content(listOf(1)))
        assertEquals(listOf("content"), shown())

        layout.render(ListScreenState.Error("timeout", listOf(1)))
        assertEquals(listOf("content"), shown())
    }

    @Test
    fun `empty shows its message without icon or retry`() {
        layout.render(ListScreenState.Empty)

        assertEquals(listOf("message"), shown())
        assertEquals("Nothing here", text(R.id.error_title))
        assertEquals(false, visible(R.id.error_icon))
        assertEquals(false, visible(R.id.error_message))
        assertEquals(false, visible(R.id.error_retry))
    }

    @Test
    fun `a failure with nothing to show explains itself and offers a retry`() {
        layout.render(ListScreenState.Error("Server unreachable", emptyList<Int>()))

        assertEquals(listOf("message"), shown())
        assertTrue(visible(R.id.error_icon))
        assertEquals("Server unreachable", text(R.id.error_message))
        message.findViewById<View>(R.id.error_retry).performClick()
        assertEquals(1, retries)
    }

    /** A screen with its own empty look shows it instead of the message slot. */
    @Test
    fun `an empty slot replaces the message for an empty answer only`() {
        val empty = View(layout.context).apply { id = R.id.state_empty }
        layout.addView(empty)

        layout.render(ListScreenState.Empty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(listOf<String>(), shown())

        layout.render(ListScreenState.Error("x", emptyList<Int>()))
        assertEquals(View.GONE, empty.visibility)
        assertEquals(listOf("message"), shown())
    }

    /** A nested StateListLayout (a list inside a detail screen) keeps its own slots. */
    @Test
    fun `only direct children are slots`() {
        val inner = StateListLayout(layout.context)
        val innerContent = View(layout.context).apply { id = R.id.state_content }
        inner.addView(innerContent)
        layout.removeView(content)
        layout.addView(inner)

        layout.render(ListScreenState.Loading)

        assertEquals(View.VISIBLE, innerContent.visibility)
    }

    @Test
    fun `no retry without a handler, and the empty wording comes back after an error`() {
        layout.onRetry = null
        layout.render(ListScreenState.Error("x", emptyList<Int>()))
        assertEquals(false, visible(R.id.error_retry))

        layout.render(ListScreenState.Empty)
        assertEquals(false, visible(R.id.error_icon))
        assertEquals(false, visible(R.id.error_message))
    }
}
