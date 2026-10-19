package com.lanraragi.reader.ui.gallery

import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.ReadingSettings

/**
 * The reader's top bar, shown together with the bottom slider as one menu:
 * back, the archive (or tank) title, the current page, and quick choices for
 * the reading direction and screen rotation next to the full settings dialog.
 *
 * [memberTitleAt] names the tank member holding a 0-indexed page; it returns
 * null outside tank sessions.
 */
class ReaderTopBar(
    root: View,
    private val callback: Callback,
    private val memberTitleAt: (Int) -> String?,
) {

    interface Callback {
        fun onTopBarBack()
        fun onTopBarSettings()

        /** A quick choice stored a new reading setting; apply the stored settings. */
        fun onTopBarSettingChanged()

        /** A choice popup opened (true) or closed (false); the menu must stay up meanwhile. */
        fun onTopBarPopup(open: Boolean)
    }

    private val title: TextView = root.findViewById(R.id.reader_top_bar_title)
    private val subtitle: TextView = root.findViewById(R.id.reader_top_bar_subtitle)

    init {
        root.findViewById<View>(R.id.reader_top_bar_back).setOnClickListener { callback.onTopBarBack() }
        root.findViewById<View>(R.id.reader_top_bar_settings).setOnClickListener { callback.onTopBarSettings() }
        root.findViewById<View>(R.id.reader_top_bar_direction).setOnClickListener { anchor ->
            showChoices(anchor, R.array.reading_direction_entries, ReadingSettings.getReadingDirection()) {
                ReadingSettings.putReadingDirection(GalleryView.sanitizeLayoutMode(it))
            }
        }
        root.findViewById<View>(R.id.reader_top_bar_rotation).setOnClickListener { anchor ->
            showChoices(anchor, R.array.screen_rotation_entries, ReadingSettings.getScreenRotation()) {
                ReadingSettings.putScreenRotation(it)
            }
        }
        // Stable insets: the bar keeps its height whether the system bars
        // are showing yet or still sliding in with it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
    }

    fun setTitle(text: CharSequence?) {
        title.text = text
    }

    /** "Page 3 / 40", with the tank member's title in tank sessions. */
    fun setPage(index: Int, size: Int) {
        if (size <= 0 || index < 0) {
            subtitle.text = null
            return
        }
        val res = subtitle.resources
        val page = res.getString(R.string.reader_top_bar_page, index + 1, size)
        val member = memberTitleAt(index)
        subtitle.text = if (member.isNullOrEmpty()) {
            page
        } else {
            res.getString(R.string.reader_top_bar_tank_page, page, member)
        }
    }

    /** A single-choice popup over [entries]; item ids are the entry positions + 1. */
    private fun showChoices(anchor: View, @ArrayRes entries: Int, checked: Int, store: (Int) -> Unit) {
        val popup = PopupMenu(anchor.context, anchor)
        anchor.resources.getStringArray(entries).forEachIndexed { position, label ->
            popup.menu.add(Menu.NONE, position + 1, position, label)
        }
        popup.menu.setGroupCheckable(Menu.NONE, true, true)
        popup.menu.findItem(checked + 1)?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            val position = item.itemId - 1
            if (position != checked) {
                store(position)
                callback.onTopBarSettingChanged()
            }
            true
        }
        popup.setOnDismissListener { callback.onTopBarPopup(false) }
        callback.onTopBarPopup(true)
        popup.show()
    }
}
