package com.lanraragi.reader.ui.scene.download

import androidx.recyclerview.widget.DiffUtil
import com.lanraragi.framework.lib.yorozuya.ObjectUtils
import com.lanraragi.reader.dao.DownloadInfo

/**
 * DiffUtil callback for DownloadInfo lists.
 *
 * Identity: [DownloadInfo.arcid]. Content comparison covers only persistent
 * / structural fields — transient progress (speed / downloaded / total /
 * finished / remaining) is intentionally excluded because it flows through
 * [DownloadListDispatcher.dispatchProgressChanges] with the `PAYLOAD_PROGRESS`
 * payload, not through DiffUtil. Including those fields here would defeat
 * that split and (prior to the W35-3b post-mortem fix) led to frozen
 * progress due to shared-reference mutation. See ADR-001 Option D.
 */
internal class DownloadInfoDiffCallback(
    private val oldList: List<DownloadInfo>,
    private val newList: List<DownloadInfo>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].arcid == newList[newItemPosition].arcid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        return oldItem.state == newItem.state &&
            oldItem.legacy == newItem.legacy &&
            ObjectUtils.equal(oldItem.label, newItem.label) &&
            ObjectUtils.equal(oldItem.title, newItem.title) &&
            ObjectUtils.equal(oldItem.thumb, newItem.thumb) &&
            // Tank cards: a membership reconcile can change ONLY the missing
            // count (Done → Incomplete) — that must re-bind the status line.
            oldItem.tankMissingCount == newItem.tankMissingCount
    }
}
