package com.lanraragi.reader.ui.reader

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.ReaderChapter
import com.lanraragi.reader.domain.ReaderChapterList
import com.lanraragi.reader.reading.ChapterIndex
import com.lanraragi.reader.reading.GalleryProvider2
import com.lanraragi.reader.ui.GalleryActivity
import kotlinx.coroutines.launch

/**
 * The reader's chapter list: the top-bar icon follows the provider's chapters,
 * and a tap opens a dialog of them. Tapping a row jumps there. View code only;
 * a provider that loads chapters lazily is told when the list opens.
 *
 * The dialog outlives a rotation (the reader handles configuration changes
 * itself) and updates in place when the list changes under it.
 */
class ReaderChaptersController(
    private val activity: GalleryActivity,
    private val provider: GalleryProvider2,
    private val currentPage: () -> Int,
    private val prepareDialog: (AlertDialog) -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var adapter: ChapterAdapter? = null
    private var userScrolled = false

    fun bind(button: View, onOpen: () -> Unit) {
        button.setOnClickListener {
            // A double tap lands before the dialog takes focus.
            if (dialog?.isShowing == true) return@setOnClickListener
            onOpen()
            show()
        }
        activity.lifecycleScope.launch {
            provider.chapters.collect { list ->
                button.isVisible = list.showEntryPoint
                render(list)
            }
        }
    }

    fun clear() {
        dialog?.dismiss()
        dialog = null
        adapter = null
    }

    private fun show() {
        val chapterAdapter = ChapterAdapter(activity)
        adapter = chapterAdapter
        userScrolled = false
        val shown = AlertDialog.Builder(activity)
            .setTitle(R.string.reader_chapters)
            .setAdapter(chapterAdapter, null)
            .setOnDismissListener {
                dialog = null
                adapter = null
            }
            .show()
        dialog = shown
        shown.listView.setOnItemClickListener { _, _, position, _ ->
            val chapter = chapterAdapter.chapterAt(position)
            if (chapter != null) {
                shown.dismiss()
                activity.jumpToPage(chapter.page0)
            } else if (chapterAdapter.list.failed) {
                provider.requestChapters()
            }
        }
        shown.listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) userScrolled = true
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
        prepareDialog(shown)
        render(provider.chapters.value)
        provider.requestChapters()
    }

    private fun render(list: ReaderChapterList) {
        val chapterAdapter = adapter ?: return
        val current = ChapterIndex.currentIndex(list.entries, currentPage())
        val rowsChanged = chapterAdapter.list.entries != list.entries
        chapterAdapter.update(list, current)
        if (rowsChanged && !userScrolled && current >= 0) {
            dialog?.listView?.setSelection(current)
        }
    }

    /** The chapters, plus one status row at the end while loading or after a failure. */
    private class ChapterAdapter(private val activity: GalleryActivity) : BaseAdapter() {
        var list = ReaderChapterList()
            private set
        private var current = -1

        private val hasStatusRow: Boolean get() = list.loading || list.failed

        fun update(list: ReaderChapterList, current: Int) {
            this.list = list
            this.current = current
            // ListView in an AlertDialog: the RecyclerView rule does not apply.
            notifyDataSetChanged()
        }

        fun chapterAt(position: Int): ReaderChapter? = list.entries.getOrNull(position)

        override fun getCount(): Int = list.entries.size + if (hasStatusRow) 1 else 0

        override fun getItem(position: Int): Any? = chapterAt(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean =
            position < list.entries.size || list.failed

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_reader_chapter, parent, false)
            val title = row.findViewById<TextView>(R.id.reader_chapter_title)
            val page = row.findViewById<TextView>(R.id.reader_chapter_page)
            val marker = row.findViewById<View>(R.id.reader_chapter_marker)
            val chapter = chapterAt(position)
            val isCurrent = position == current
            row.isSelected = isCurrent
            marker.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            val indent = activity.resources.getDimensionPixelSize(R.dimen.reader_chapter_indent)
            val depth = chapter?.depth ?: 0
            title.setPaddingRelative(
                activity.resources.getDimensionPixelSize(R.dimen.reader_chapter_padding) + depth * indent,
                title.paddingTop,
                title.paddingEnd,
                title.paddingBottom,
            )
            if (chapter == null) {
                title.setText(if (list.failed) R.string.reader_chapters_failed else R.string.reader_chapters_loading)
                page.text = null
            } else {
                title.text = chapter.title.ifBlank { activity.getString(R.string.reader_chapters_untitled) }
                page.text = activity.getString(R.string.reader_chapter_page, chapter.page0 + 1)
            }
            return row
        }
    }
}
