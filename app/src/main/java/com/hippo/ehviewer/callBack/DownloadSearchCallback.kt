package com.hippo.ehviewer.callBack

import com.hippo.ehviewer.dao.DownloadInfo

interface DownloadSearchCallback {
    fun onDownloadSearchSuccess(mList: List<DownloadInfo>)
    fun onDownloadListHandleSuccess(mList: List<DownloadInfo>)
    fun onDownloadSearchFailed(mList: List<DownloadInfo>)
}
