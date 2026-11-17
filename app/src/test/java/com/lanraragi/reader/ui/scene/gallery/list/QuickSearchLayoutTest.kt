package com.lanraragi.reader.ui.scene.gallery.list

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.lanraragi.framework.widget.ProgressView
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.list.ListScreenState
import com.lanraragi.reader.widget.StateListLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The quick-search screen's own layout: its slots render each list state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class QuickSearchLayoutTest {

    private lateinit var list: StateListLayout

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        list = LayoutInflater.from(context).inflate(R.layout.scene_quick_search, null) as StateListLayout
    }

    private fun visible(id: Int) = list.findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun `loading spins, the list shows its rows, nothing saved shows the tip`() {
        list.render(ListScreenState.Loading)
        assertTrue(list.findViewById<View>(R.id.state_progress) is ProgressView)
        assertTrue(visible(R.id.state_progress))
        assertEquals(false, visible(R.id.state_content))

        list.render(ListScreenState.Content(listOf("a")))
        assertTrue(visible(R.id.state_content))
        assertEquals(false, visible(R.id.state_progress))

        list.render(ListScreenState.Empty)
        assertTrue(visible(R.id.state_empty))
        assertEquals(false, visible(R.id.state_message))
    }

    @Test
    fun `a failed load shows the error with its message`() {
        list.render(ListScreenState.Error("Database closed", emptyList<String>()))

        assertTrue(visible(R.id.state_message))
        assertEquals(false, visible(R.id.state_empty))
    }
}
