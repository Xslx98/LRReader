package com.hippo.ehviewer.dao

import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.settings.AppearanceSettings
import com.lanraragi.reader.client.api.LRRAuthManager

/**
 * Repository for history-related database operations, backed by [BrowsingRoomDao].
 *
 * This is the first domain repository extracted from [com.hippo.ehviewer.EhDB] as part
 * of the incremental God Object decomposition. It is a thin delegation layer — no
 * business logic beyond what EhDB already had (profile filtering, timestamp, trimming).
 *
 * Registered as a lazy val in [com.hippo.ehviewer.module.DataModule].
 */
class HistoryRepository(private val dao: BrowsingRoomDao) {

    suspend fun getHistoryLazyList(): List<HistoryInfo> {
        val profileId = LRRAuthManager.getActiveProfileId()
        return if (profileId > 0)
            dao.getHistoryByServer(profileId)
        else
            dao.getAllHistory()
    }

    suspend fun putHistoryInfo(galleryInfo: GalleryInfo) {
        val info = HistoryInfo(galleryInfo)
        info.time = System.currentTimeMillis()
        dao.insertHistory(info)
        val maxCount = AppearanceSettings.getHistoryInfoSize().let {
            if (it < 1) 100 else it
        }
        dao.trimHistoryTo(maxCount)
    }

    suspend fun putHistoryInfoList(historyInfoList: List<HistoryInfo>) {
        for (info in historyInfoList) {
            dao.insertHistory(info)
        }
        val maxCount = AppearanceSettings.getHistoryInfoSize().let {
            if (it < 1) 100 else it
        }
        dao.trimHistoryTo(maxCount)
    }

    suspend fun deleteHistoryInfo(info: HistoryInfo) {
        dao.deleteHistoryByKey(info.gid)
    }

    suspend fun clearHistory() {
        dao.deleteAllHistory()
    }
}
