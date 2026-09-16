package com.lanraragi.reader.ui.scene.download

import androidx.lifecycle.ViewModel
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.DownloadLabel
import com.lanraragi.reader.download.DownloadManager

/**
 * ViewModel for [DownloadLabelsScene]. Manages download label CRUD
 * operations through [DownloadManager] facade.
 *
 * The Scene retains ownership of View creation, dialog builders,
 * RecyclerView adapter, and drag-drop manager. The ViewModel owns
 * the label list state and all DownloadManager interactions.
 */
class DownloadLabelsViewModel : ViewModel() {

    private val downloadManager: DownloadManager =
        ServiceRegistry.dataModule.downloadManager

    /** The current label list. Backed by DownloadManager's mutable list. */
    val labels: List<DownloadLabel>
        get() = downloadManager.labelList

    fun containsLabel(name: String?): Boolean = downloadManager.containLabel(name)

    fun addLabel(name: String?) = downloadManager.addLabel(name)

    fun renameLabel(oldName: String, newName: String?) = downloadManager.renameLabel(oldName, newName ?: "")

    fun deleteLabel(label: String) = downloadManager.deleteLabel(label)

    fun moveLabel(fromPosition: Int, toPosition: Int) = downloadManager.moveLabel(fromPosition, toPosition)
}
