package com.hippo.ehviewer.ui.scene.download

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.scene.Announcer

/**
 * Manages the drawer view showing download labels.
 * Refactored in W16-1 to use a [Callback] interface instead of direct Scene references.
 */
class DownloadLabelDraw(
    private val inflater: LayoutInflater,
    private val container: ViewGroup?,
    private val callback: Callback
) {

    interface Callback {
        val ehContext: Context?
        val currentLabel: String?
        val searching: Boolean
        val searchKey: String?
        val downloadManager: DownloadManager
        fun getString(resId: Int): String
        fun startScene(announcer: Announcer)
        fun selectLabel(label: String?)
        fun updateForLabel()
        fun startSearching()
        fun updateView()
        fun closeDrawer(gravity: Int)
    }

    private val context: Context = callback.ehContext!!

    private lateinit var view: View
    private lateinit var toolbar: Toolbar
    private lateinit var listView: ListView

    fun createView(): View {
        view = inflater.inflate(R.layout.bookmarks_draw, container, false)

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setTitle(R.string.download_labels)
        toolbar.inflateMenu(R.menu.drawer_download)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    callback.startScene(Announcer(DownloadLabelsScene::class.java))
                    true
                }
                R.id.action_default_download_label -> {
                    val dm = callback.downloadManager
                    val list = dm.labelList
                    val items = arrayOfNulls<String>(list.size + 2)
                    items[0] = callback.getString(R.string.let_me_select)
                    items[1] = callback.getString(R.string.default_download_label_name)
                    for (i in list.indices) {
                        items[i + 2] = list[i].label
                    }
                    AlertDialog.Builder(context)
                        .setTitle(R.string.default_download_label)
                        .setItems(items) { _, which ->
                            if (which == 0) {
                                DownloadSettings.putHasDefaultDownloadLabel(false)
                            } else {
                                DownloadSettings.putHasDefaultDownloadLabel(true)
                                val label = if (which == 1) null else items[which]
                                DownloadSettings.putDefaultDownloadLabel(label)
                            }
                        }.show()
                    true
                }
                else -> false
            }
        }

        val downloadManager = ServiceRegistry.dataModule.downloadManager

        val list = downloadManager.labelList
        val labels = ArrayList<String>(list.size + 1)
        // Add default label name
        labels.add(callback.getString(R.string.default_download_label_name))
        for (raw in list) {
            labels.add(raw.label ?: "")
        }

        // KNOWN-ISSUE (P2): download label items update not yet wired to adapter notification
        val downloadLabelList = ArrayList<DownloadLabelItem>()

        for (i in labels.indices) {
            val label = labels[i]
            if (i == 0) {
                downloadLabelList.add(DownloadLabelItem(label, downloadManager.defaultDownloadInfoList.size.toLong()))
                continue
            }
            downloadLabelList.add(DownloadLabelItem(label, downloadManager.getLabelCount(label)))
        }

        listView = view.findViewById(R.id.list_view)
        val adapter = DownloadLabelAdapter(
            context,
            R.layout.item_download_label_list,
            downloadLabelList
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            onLabelItemClick(position, labels)
        }
        return view
    }

    fun updateDownloadLabels() {
        val downloadManager = ServiceRegistry.dataModule.downloadManager
        val list = downloadManager.labelList
        val labels = ArrayList<String>(list.size + 1)
        // Add default label name
        labels.add(callback.getString(R.string.default_download_label_name))
        for (raw in list) {
            labels.add(raw.label ?: "")
        }

        // KNOWN-ISSUE (P2): download label items update not yet wired to adapter notification
        val downloadLabelList = ArrayList<DownloadLabelItem>()

        for (i in labels.indices) {
            val label = labels[i]
            if (i == 0) {
                downloadLabelList.add(DownloadLabelItem(label, downloadManager.defaultDownloadInfoList.size.toLong()))
                continue
            }
            downloadLabelList.add(DownloadLabelItem(label, downloadManager.getLabelCount(label)))
        }

        val adapter = DownloadLabelAdapter(
            context,
            R.layout.item_download_label_list,
            downloadLabelList
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            onLabelItemClick(position, labels)
        }
    }

    private fun onLabelItemClick(position: Int, labels: List<String>) {
        if (callback.searching) {
            Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show()
            return
        }
        val label = if (position == 0) null else labels[position]
        if (!ObjectUtils.equal(label, callback.currentLabel)) {
            callback.selectLabel(label)
            callback.updateForLabel()
            if (!callback.searchKey.isNullOrEmpty()) {
                callback.startSearching()
            } else {
                callback.updateView()
            }
            callback.closeDrawer(Gravity.RIGHT)
        }
    }
}
