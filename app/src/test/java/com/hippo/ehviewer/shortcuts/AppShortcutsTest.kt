package com.hippo.ehviewer.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dynamic registration of the formerly-static launcher shortcuts (INF-10,
 * issue #17): start_all/stop_all target [ShortcutsActivity] via context-built
 * intents — no hardcoded targetPackage, so they exist on the .debug package
 * too. Registration is idempotent and must not clobber other dynamic
 * shortcuts (the continue-reading entry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AppShortcutsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    @Test
    fun register_addsBothDownloadShortcuts_targetingShortcutsActivity() {
        AppShortcuts.registerBaseline(context)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(setOf("start_all", "stop_all"), shortcuts.map { it.id }.toSet())
        shortcuts.forEach { s ->
            assertEquals(ShortcutsActivity::class.java.name, s.intent!!.component!!.className)
            assertEquals(s.id, s.intent!!.action)
        }
    }

    @Test
    fun register_isIdempotent() {
        AppShortcuts.registerBaseline(context)
        AppShortcuts.registerBaseline(context)

        assertEquals(2, ShortcutManagerCompat.getDynamicShortcuts(context).size)
    }

    @Test
    fun register_doesNotClobberOtherDynamicShortcuts() {
        val other = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, "continue_reading")
            .setShortLabel("x")
            .setIntent(android.content.Intent(context, ShortcutsActivity::class.java).setAction("noop"))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, other)

        AppShortcuts.registerBaseline(context)

        val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.toSet()
        assertTrue("continue_reading" in ids)
        assertTrue("start_all" in ids && "stop_all" in ids)
    }
}
