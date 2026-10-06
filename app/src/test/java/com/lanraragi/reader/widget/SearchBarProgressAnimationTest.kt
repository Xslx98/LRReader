package com.lanraragi.reader.widget

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Opening and closing the suggestion list animates SearchBar's progress through a
 * typed property instead of a "progress" string resolved by reflection (audit
 * 2026-10-06e STAB-02); this pins that the animation still reaches both ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchBarProgressAnimationTest {

    @Test(timeout = 10_000)
    fun suggestionListAnimatesProgressOpenAndClosed() {
        val bar = SearchBar(
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme_Main)
        )

        bar.setState(SearchBar.STATE_SEARCH_LIST, true)
        ShadowLooper.idleMainLooper(ADVANCE_MS, TimeUnit.MILLISECONDS)
        assertEquals(1f, bar.getProgress(), 0f)

        bar.setState(SearchBar.STATE_SEARCH, true)
        ShadowLooper.idleMainLooper(ADVANCE_MS, TimeUnit.MILLISECONDS)
        assertEquals(0f, bar.getProgress(), 0f)
    }

    private companion object {
        const val ADVANCE_MS = 1_000L
    }
}
