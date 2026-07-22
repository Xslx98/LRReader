package com.hippo.ehviewer.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hippo.ehviewer.R

/**
 * Dynamic registration of the app's baseline launcher shortcuts (INF-10,
 * issue #17). The former static shortcuts.xml hardcoded
 * targetPackage="com.lanraragi.reader", which silently hid them on the .debug
 * application id; context-built intents resolve the real package.
 *
 * Registered per shortcut via push (same-id replace, idempotent) — never via
 * setDynamicShortcuts, which would clobber the continue-reading shortcut.
 */
object AppShortcuts {

    fun registerBaseline(context: Context) {
        push(context, "start_all", R.string.download_start_all, R.drawable.ic_shortcut_start)
        push(context, "stop_all", R.string.download_stop_all, R.drawable.ic_shortcut_stop)
    }

    private fun push(context: Context, id: String, labelRes: Int, iconRes: Int) {
        val label = context.getString(labelRes)
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(Intent(context, ShortcutsActivity::class.java).setAction(id))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }
}
