package com.lanraragi.reader.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.GuideSettings
import com.lanraragi.reader.tagtranslation.TagTranslationDatabase
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.tagtranslation.TagTranslationUtil
import com.lanraragi.framework.util.AssertUtils
import com.lanraragi.framework.util.ViewUtils
import com.lanraragi.framework.scene.Announcer

class BookmarksDraw(
    private val context: Context,
    private val inflater: LayoutInflater,
    ehTags: TagTranslationDatabase?
) {

    private val ehTags: TagTranslationDatabase? = ehTags ?: TagTranslationDatabase.getInstance(context)
    private lateinit var listView: ListView
    private lateinit var viewModel: QuickSearchViewModel

    @SuppressLint("NonConstantResourceId")
    fun onCreate(scene: GalleryListScene, parent: ViewGroup): View {
        val bookmarksView = inflater.inflate(R.layout.bookmarks_draw, parent, false)

        val toolbar = ViewUtils.requireView(bookmarksView, R.id.toolbar) as Toolbar
        val tip = ViewUtils.requireView(bookmarksView, R.id.tip) as TextView
        listView = ViewUtils.requireView(bookmarksView, R.id.list_view) as ListView
        viewModel = ViewModelProvider(scene)[QuickSearchViewModel::class.java]

        AssertUtils.assertNotNull(context)

        listView.setOnScrollListener(ScrollListener())

        tip.setText(R.string.quick_search_tip)
        toolbar.setLogo(R.drawable.ic_baseline_bookmarks_24)
        toolbar.setTitle(R.string.quick_search)
        toolbar.inflateMenu(R.menu.drawer_gallery_list)

        // Translated tag names are persisted by the ViewModel; with translations
        // off nothing is rewritten (see QuickSearchNameReconciler for the history).
        viewModel.loadForDrawer(
            AppearanceSettings.getShowTagTranslations(),
            { parts -> TagTranslationUtil.getTagCN(parts, this@BookmarksDraw.ehTags) },
        ) { list ->
            val adapter = ArrayAdapter(context, R.layout.item_simple_list, list)
            listView.adapter = adapter
            // Quick search click tag event listener
            listView.setOnItemClickListener { _, _, position, _ ->
                val urlBuilder = scene.mUrlBuilder ?: return@setOnItemClickListener
                val helper = scene.mHelper ?: return@setOnItemClickListener

                urlBuilder.set(list[position])
                urlBuilder.setPageIndex(0)
                // Quick-search launches are real searches: record the
                // keyword (triage decision, issue #12 — recording is
                // source-agnostic).
                list[position].keyword?.takeIf { it.isNotBlank() }
                    ?.let { scene.mHistoryStore?.record(it) }
                scene.onUpdateUrlBuilder()
                helper.refresh()
                scene.setState(GalleryListScene.STATE_NORMAL)
                scene.closeDrawer(Gravity.RIGHT)
            }

            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add -> {
                        if (GuideSettings.getQuickSearchTip()) {
                            scene.showQuickSearchTipDialog(list, adapter, listView, tip)
                        } else {
                            scene.showAddQuickSearchDialog(list, adapter, listView, tip)
                        }
                    }
                    R.id.action_settings -> {
                        scene.startScene(Announcer(QuickSearchScene::class.java))
                    }
                }
                true
            }

            if (list.isEmpty()) {
                tip.visibility = View.VISIBLE
                listView.visibility = View.GONE
            } else {
                tip.visibility = View.GONE
                listView.visibility = View.VISIBLE
                resume()
            }
        }

        toolbar.setOnClickListener {
            scene.drawPager?.currentItem = 1
        }

        return bookmarksView
    }

    fun resume() {
        viewModel.drawerScrollPosition?.let { listView.setSelection(it) }
    }

    private inner class ScrollListener : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            // Only once the list has rows, as before.
            if (view.getChildAt(0) == null) return
            viewModel.drawerScrollPosition = view.firstVisiblePosition
        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
    }
}
