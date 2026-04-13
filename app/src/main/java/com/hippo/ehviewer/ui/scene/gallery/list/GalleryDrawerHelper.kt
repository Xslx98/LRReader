package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.app.EditTextDialogBuilder
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.util.TagTranslationUtil
import kotlinx.coroutines.launch

/**
 * Handles drawer view creation, bookmarks, and quick search dialogs
 * for GalleryListScene. Extracted to reduce GalleryListScene's line count.
 *
 * The legacy E-Hentai "subscription" pane (watched/hidden tag list) was
 * removed along with EhClient — LANraragi has no equivalent concept.
 */
class GalleryDrawerHelper(private val callback: Callback) {

    interface Callback {
        fun getHostContext(): Context?
        fun getHostActivity(): Activity?
        fun getScene(): GalleryListScene
        fun getSceneTag(): String?
        fun getUrlBuilder(): ListUrlBuilder?
        fun getEhTags(): EhTagDatabase?
        fun showTip(resId: Int, length: Int)
        fun showTip(message: String, length: Int)
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
        fun onTagClick(tagName: String)
    }

    private var mBookmarksDraw: BookmarksDraw? = null
    lateinit var drawPager: ViewPager
        private set
    private lateinit var bookmarksView: View

    @SuppressLint("RtlHardcoded", "NonConstantResourceId")
    fun onCreateDrawerView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.drawer_list, container, false)

        drawPager = view.findViewById(R.id.drawer_list_pager)

        bookmarksView = bookmarksViewBuild(inflater)

        val views: MutableList<View> = ArrayList()
        views.add(bookmarksView)

        val pagerAdapter = DrawViewPagerAdapter(views)
        drawPager.adapter = pagerAdapter

        return view
    }

    @SuppressLint("RtlHardcoded", "NonConstantResourceId")
    private fun bookmarksViewBuild(inflater: LayoutInflater): View {
        val context = callback.getHostContext() ?: return View(null)
        mBookmarksDraw = BookmarksDraw(context, inflater, callback.getEhTags())
        return mBookmarksDraw!!.onCreate(callback.getScene())
    }

    fun onResume() {
        mBookmarksDraw?.resume()
    }

    // Quick search dialogs

    fun showQuickSearchTipDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        val context = callback.getHostContext() ?: return
        val builder = CheckBoxDialogBuilder(
            context, callback.getString(R.string.add_quick_search_tip),
            callback.getString(R.string.get_it), false
        )
        builder.setTitle(R.string.readme)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            if (builder.isChecked) {
                GuideSettings.putQuickSearchTip(false)
            }
            showAddQuickSearchDialog(list, adapter, listView, tip)
        }.show()
    }

    fun showAddQuickSearchDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        val translation = AppearanceSettings.getShowTagTranslations()
        val context = callback.getHostContext()
        val urlBuilder = callback.getUrlBuilder()
        if (context == null || urlBuilder == null) {
            return
        }

        if (ListUrlBuilder.MODE_IMAGE_SEARCH == urlBuilder.mode) {
            callback.showTip(R.string.image_search_not_quick_search, BaseScene.LENGTH_LONG)
            return
        }

        for (q in list) {
            if (urlBuilder.equalsQuickSearch(q)) {
                callback.showTip(
                    callback.getString(R.string.duplicate_quick_search, q.name ?: ""),
                    BaseScene.LENGTH_LONG
                )
                return
            }
        }

        val builder = EditTextDialogBuilder(
            context,
            GallerySearchHelper.getSuitableTitleForUrlBuilder(context.resources, urlBuilder, false),
            callback.getString(R.string.quick_search)
        )
        builder.setTitle(R.string.add_quick_search_dialog_title)
        builder.setPositiveButton(android.R.string.ok, null)
        val dialog = builder.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val text = builder.text.trim()

            if (TextUtils.isEmpty(text)) {
                builder.setError(callback.getString(R.string.name_is_empty))
                return@setOnClickListener
            }

            for (q in list) {
                if (text == q.name) {
                    builder.setError(callback.getString(R.string.duplicate_name))
                    return@setOnClickListener
                }
            }

            builder.setError(null)
            dialog.dismiss()
            val quickSearch = urlBuilder.toQuickSearch()

            if (translation) {
                val ehTags = callback.getEhTags()
                val parts = text.split("  ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val newText = StringBuilder()
                for (part in parts) {
                    val tags = part.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    for (j in tags.indices) {
                        tags[j] = tags[j].replace("\"", "").replace("$", "")
                    }
                    val trans = TagTranslationUtil.getTagCN(tags, ehTags)
                    if (newText.isEmpty()) {
                        newText.append(trans)
                    } else {
                        newText.append("  ").append(trans)
                    }
                }
                quickSearch.name = newText.toString()
            } else {
                quickSearch.name = text
            }
            val activity = callback.getHostActivity()
            ServiceRegistry.coroutineModule.ioScope.launch {
                EhDB.insertQuickSearchAsync(quickSearch)
                activity?.runOnUiThread {
                    @Suppress("UNCHECKED_CAST")
                    (list as MutableList<QuickSearch>).add(quickSearch)
                    // ArrayAdapter only supports notifyDataSetChanged() — no granular notifications
                    @Suppress("NotifyDataSetChanged")
                    adapter.notifyDataSetChanged()
                    if (list.isEmpty()) {
                        tip.visibility = View.VISIBLE
                        listView.visibility = View.GONE
                    } else {
                        tip.visibility = View.GONE
                        listView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
