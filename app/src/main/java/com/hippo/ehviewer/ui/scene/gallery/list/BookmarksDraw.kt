package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.util.TagTranslationUtil
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.scene.Announcer

class BookmarksDraw(
    private val context: Context,
    private val inflater: LayoutInflater,
    ehTags: EhTagDatabase?
) {

    private val ehTags: EhTagDatabase? = ehTags ?: EhTagDatabase.getInstance(context)
    private val ehApplication: EhApplication = context.applicationContext as EhApplication

    private lateinit var listView: ListView

    @SuppressLint("NonConstantResourceId")
    fun onCreate(scene: GalleryListScene): View {
        val bookmarksView = inflater.inflate(R.layout.bookmarks_draw, null, false)

        val toolbar = ViewUtils.`$$`(bookmarksView, R.id.toolbar) as Toolbar
        val tip = ViewUtils.`$$`(bookmarksView, R.id.tip) as TextView
        listView = ViewUtils.`$$`(bookmarksView, R.id.list_view) as ListView

        AssertUtils.assertNotNull(context)

        listView.setOnScrollListener(ScrollListener())

        tip.setText(R.string.quick_search_tip)
        toolbar.setLogo(R.drawable.ic_baseline_bookmarks_24)
        toolbar.setTitle(R.string.quick_search)
        toolbar.inflateMenu(R.menu.drawer_gallery_list)

        com.hippo.util.IoThreadPoolExecutor.instance.execute {
            val quickSearchList = EhDB.getAllQuickSearch()
            // tag translation updates are persisted on IO thread
            val judge = AppearanceSettings.getShowTagTranslations()
            val toUpdate = mutableListOf<QuickSearch>()
            if (judge && quickSearchList.isNotEmpty()) {
                for (i in quickSearchList.indices) {
                    val name = quickSearchList[i].name
                    if (name != null && name.split(":").size == 2) {
                        quickSearchList[i].name = TagTranslationUtil.getTagCN(name.split(":").toTypedArray(), this.ehTags)
                        toUpdate.add(quickSearchList[i])
                    }
                }
            } else if (!judge && quickSearchList.isNotEmpty()) {
                for (i in quickSearchList.indices) {
                    val name = quickSearchList[i].name
                    if (name != null && name.split(":").size == 1) {
                        quickSearchList[i].name = quickSearchList[i].keyword
                        toUpdate.add(quickSearchList[i])
                    }
                }
            }
            if (toUpdate.isNotEmpty()) {
                for (qs in toUpdate) EhDB.updateQuickSearch(qs)
            }

            val list = quickSearchList

            listView.post {
                val adapter = ArrayAdapter(context, R.layout.item_simple_list, list)
                listView.adapter = adapter
                // Quick search click tag event listener
                listView.setOnItemClickListener { _, _, position, _ ->
                    val urlBuilder = scene.mUrlBuilder ?: return@setOnItemClickListener
                    val helper = scene.mHelper ?: return@setOnItemClickListener

                    urlBuilder.set(list[position])
                    urlBuilder.setPageIndex(0)
                    scene.onUpdateUrlBuilder()
                    helper.refresh()
                    scene.setState(GalleryListScene.STATE_NORMAL)
                    scene.closeDrawer(Gravity.RIGHT)
                }

                toolbar.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_add -> {
                            if (Settings.getQuickSearchTip()) {
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
        }

        toolbar.setOnClickListener {
            scene.drawPager?.currentItem = 1
        }

        return bookmarksView
    }

    fun resume() {
        val scrollY = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_Y)
        val pos = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_POS)
        if (scrollY != null && pos != null) {
            listView.setSelection(pos as Int)
        }
    }

    private inner class ScrollListener : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            val item = view.getChildAt(0) ?: return
            val firstPos = view.firstVisiblePosition
            val top = item.top
            val scrollY = firstPos * item.height - top
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_Y, scrollY)
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_POS, firstPos)
        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
    }

    companion object {
        private const val QUICK_SEARCH_DRAW_SCROLL_Y = "QuickSearchDrawScrollY"
        private const val QUICK_SEARCH_DRAW_SCROLL_POS = "QuickSearchDrawScrollPos"
    }
}
