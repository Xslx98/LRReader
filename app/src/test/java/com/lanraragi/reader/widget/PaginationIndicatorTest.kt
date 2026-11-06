package com.lanraragi.reader.widget

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PaginationIndicatorTest {

    private val choices = intArrayOf(50, 100, 200)
    private val events = mutableListOf<String>()
    private lateinit var indicator: PaginationIndicator

    @Before
    fun setUp() {
        indicator = PaginationIndicator(
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme),
        )
        indicator.listener = object : PaginationIndicator.OnChangedListener {
            override fun onPageSelected(page: Int) {
                events += "page $page"
            }

            override fun onPageSizeSelected(pageSize: Int) {
                events += "size $pageSize"
            }
        }
    }

    private val previous get() = indicator.findViewById<TextView>(R.id.pagination_previous)
    private val next get() = indicator.findViewById<TextView>(R.id.pagination_next)
    private val spinner get() = indicator.findViewById<Spinner>(R.id.pagination_page_size)

    private fun visibleTips(): List<TextView> {
        val row = indicator.findViewById<LinearLayout>(R.id.pagination_tips)
        return (0 until row.childCount).map { row.getChildAt(it) as TextView }
            .filter { it.visibility == View.VISIBLE }
    }

    private fun tipTexts() = visibleTips().map { it.text.toString() }

    private fun selectedTip() = visibleTips().single { it.isSelected }.text.toString()

    /** Reports a spinner selection the way the platform does after the user picks a row. */
    private fun pickPageSize(position: Int) {
        spinner.onItemSelectedListener!!.onItemSelected(spinner, null, position, position.toLong())
    }

    @Test
    fun `bind shows the state without calling the listener`() {
        indicator.bind(total = 600, pageSize = 100, page = 3, pageSizeChoices = choices)

        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("1", "2", "3", "4", "5"), tipTexts())
        assertEquals("3", selectedTip())
        assertEquals(1, spinner.selectedItemPosition)
    }

    @Test
    fun `arrows follow the page after a rebind in the middle of the list`() {
        indicator.bind(total = 600, pageSize = 100, page = 3, pageSizeChoices = choices)
        assertTrue(previous.isEnabled)
        assertTrue(next.isEnabled)

        indicator.bind(total = 600, pageSize = 100, page = 6, pageSizeChoices = choices)
        assertTrue(previous.isEnabled)
        assertFalse(next.isEnabled)

        indicator.bind(total = 600, pageSize = 100, page = 1, pageSizeChoices = choices)
        assertFalse(previous.isEnabled)
        assertTrue(next.isEnabled)
    }

    @Test
    fun `a page past the end displays as the last page`() {
        indicator.bind(total = 300, pageSize = 100, page = 9, pageSizeChoices = choices)

        assertEquals("3", selectedTip())
        assertFalse(next.isEnabled)
    }

    @Test
    fun `tapping a page number selects that page and slides the row`() {
        indicator.bind(total = 1000, pageSize = 100, page = 3, pageSizeChoices = choices)

        visibleTips().last().performClick()

        assertEquals(listOf("page 5"), events)
        assertEquals("5", selectedTip())
        assertEquals(listOf("3", "4", "5", "6", "7"), tipTexts())
    }

    @Test
    fun `tapping the current page reports nothing`() {
        indicator.bind(total = 1000, pageSize = 100, page = 3, pageSizeChoices = choices)

        visibleTips().single { it.isSelected }.performClick()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `arrows move one page and stop at the ends`() {
        indicator.bind(total = 200, pageSize = 100, page = 1, pageSizeChoices = choices)

        next.performClick()
        assertEquals(listOf("page 2"), events)
        assertFalse(next.isEnabled)

        previous.performClick()
        assertEquals(listOf("page 2", "page 1"), events)
        assertFalse(previous.isEnabled)
    }

    @Test
    fun `picking a page size reports it once and returns to page 1`() {
        indicator.bind(total = 1000, pageSize = 100, page = 7, pageSizeChoices = choices)

        pickPageSize(0)

        assertEquals(listOf("size 50"), events)
        assertEquals("1", selectedTip())
    }

    @Test
    fun `the selection report after layout is not taken for a user choice`() {
        indicator.bind(total = 1000, pageSize = 100, page = 7, pageSizeChoices = choices)

        pickPageSize(1)

        assertEquals(emptyList<String>(), events)
        assertEquals("7", selectedTip())
    }
}
