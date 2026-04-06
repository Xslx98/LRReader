package com.hippo.ehviewer.ui.scene.download

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
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.lib.yorozuya.ObjectUtils

class DownloadLabelDraw(
    private val inflater: LayoutInflater,
    private val container: ViewGroup?,
    private val scene: DownloadsScene
) {
    private val context = scene.ehContext!!

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
                    scene.startScene(com.hippo.scene.Announcer(DownloadLabelsScene::class.java))
                    true
                }
                R.id.action_default_download_label -> {
                    val dm = scene.getMDownloadManager() ?: return@setOnMenuItemClickListener true

                    val list = dm.labelList
                    val items = arrayOfNulls<String>(list.size + 2)
                    items[0] = scene.getString(R.string.let_me_select)
                    items[1] = scene.getString(R.string.default_download_label_name)
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
        labels.add(scene.getString(R.string.default_download_label_name))
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
            scene.ehContext!!,
            R.layout.item_download_label_list,
            downloadLabelList
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (scene.searching) {
                Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show()
                return@setOnItemClickListener
            }
            val label = if (position == 0) null else labels[position]
            if (!ObjectUtils.equal(label, scene.mLabel)) {
                scene.mLabel = label
                scene.updateForLabel()
                if (!scene.searchKey.isNullOrEmpty()) {
                    scene.startSearching()
                } else {
                    scene.updateView()
                }
                scene.closeDrawer(Gravity.RIGHT)
            }
        }
        return view
    }

    fun updateDownloadLabels() {
        val downloadManager = ServiceRegistry.dataModule.downloadManager
        val list = downloadManager.labelList
        val labels = ArrayList<String>(list.size + 1)
        // Add default label name
        labels.add(scene.getString(R.string.default_download_label_name))
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
            scene.ehContext!!,
            R.layout.item_download_label_list,
            downloadLabelList
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (scene.searching) {
                Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show()
                return@setOnItemClickListener
            }
            val label = if (position == 0) null else labels[position]
            if (!ObjectUtils.equal(label, scene.mLabel)) {
                scene.mLabel = label
                scene.updateForLabel()
                if (!scene.searchKey.isNullOrEmpty()) {
                    scene.startSearching()
                } else {
                    scene.updateView()
                }
                scene.closeDrawer(Gravity.RIGHT)
            }
        }
    }
}
