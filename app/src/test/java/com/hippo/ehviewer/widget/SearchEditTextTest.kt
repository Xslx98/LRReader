package com.hippo.ehviewer.widget

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchEditTextTest {

    private class RecordingListener : SearchEditText.SearchEditTextListener {
        var clicks = 0
        override fun onClick() { clicks++ }
        override fun onBackPressed() = Unit
    }

    @Test
    fun `performClick notifies listener`() {
        val view = SearchEditText(ApplicationProvider.getApplicationContext())
        val listener = RecordingListener()
        view.setSearchEditTextListener(listener)

        view.performClick()

        assertTrue(listener.clicks >= 1)
    }

    @Test
    fun `touch up notifies listener`() {
        val view = SearchEditText(ApplicationProvider.getApplicationContext())
        val listener = RecordingListener()
        view.setSearchEditTextListener(listener)

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        val up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 0f, 0f, 0)
        view.onTouchEvent(down)
        view.onTouchEvent(up)
        down.recycle()
        up.recycle()

        assertTrue(listener.clicks >= 1)
    }
}
