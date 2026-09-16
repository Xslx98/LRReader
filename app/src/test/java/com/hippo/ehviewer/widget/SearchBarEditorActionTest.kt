package com.hippo.ehviewer.widget

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hardware Enter key reaches [SearchBar.onEditorAction] twice: TextView
 * invokes the editor-action listener with IME_NULL on key DOWN and, when
 * that returned true, again on key UP. Before the fix both invocations ran
 * applySearch, so one Enter dispatched the same search (and history record)
 * twice — observed as duplicate /api/search requests on the emulator. The
 * soft keyboard's search key (IME_ACTION_SEARCH, no key event) fires once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SearchBarEditorActionTest {

    private class RecordingHelper : SearchBar.Helper {
        val applied = mutableListOf<String>()
        override fun onClickTitle() = Unit
        override fun onClickLeftIcon() = Unit
        override fun onClickRightIcon() = Unit
        override fun onSearchEditTextClick() = Unit
        override fun onApplySearch(query: String) { applied.add(query) }
        override fun onSearchEditTextBackPressed() = Unit
    }

    private fun newSearchBar(helper: RecordingHelper): Pair<SearchBar, TextView> {
        val bar = SearchBar(
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme_Main)
        )
        bar.setHelper(helper)
        bar.setText("touhou")
        val edit = bar.findViewById<TextView>(R.id.search_edit_text)
        return bar to edit
    }

    @Test
    fun `hardware enter down+up applies the search exactly once`() {
        val helper = RecordingHelper()
        val (bar, edit) = newSearchBar(helper)

        val down = bar.onEditorAction(edit, EditorInfo.IME_NULL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        val up = bar.onEditorAction(edit, EditorInfo.IME_NULL, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertTrue("down must be consumed", down)
        assertTrue("up must be consumed so TextView does not move focus", up)
        assertEquals(listOf("touhou"), helper.applied)
    }

    @Test
    fun `soft keyboard search action applies once`() {
        val helper = RecordingHelper()
        val (bar, edit) = newSearchBar(helper)

        val handled = bar.onEditorAction(edit, EditorInfo.IME_ACTION_SEARCH, null)

        assertTrue(handled)
        assertEquals(listOf("touhou"), helper.applied)
    }

    @Test
    fun `other actions and other views are ignored`() {
        val helper = RecordingHelper()
        val (bar, edit) = newSearchBar(helper)

        assertEquals(false, bar.onEditorAction(edit, EditorInfo.IME_ACTION_DONE, null))
        assertEquals(false, bar.onEditorAction(TextView(bar.context), EditorInfo.IME_ACTION_SEARCH, null))
        assertEquals(emptyList<String>(), helper.applied)
    }
}
