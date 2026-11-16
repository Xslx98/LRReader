package com.lanraragi.reader.download

/**
 * Progress callbacks from one [LRRDownloadWorker] run; [DownloadScheduler]
 * bridges them onto the main thread.
 */
internal interface DownloadWorkerListener {
    fun onGetPages(pages: Int)
    fun onGet509(index: Int)
    fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int)
    fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int)
    fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int)
    /** Called once when the run ends (never after a cancel). */
    fun onFinish(result: WorkerResult)
}
