package com.lanraragi.reader.download.search

import com.lanraragi.reader.dao.DownloadInfo

interface DownloadSearchCallback {
    fun onDownloadSearchSuccess(mList: List<DownloadInfo>)
    fun onDownloadListHandleSuccess(mList: List<DownloadInfo>)
    fun onDownloadSearchFailed(mList: List<DownloadInfo>)
}
