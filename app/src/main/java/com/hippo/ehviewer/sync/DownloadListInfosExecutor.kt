package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.callBack.DownloadSearchCallback
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.GalleryTags
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.unifile.UniFile
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DownloadListInfosExecutor {

    @Suppress("unused")
    private val TAG = "DownloadSearchingExecutor"

    private val service: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler: Handler = Handler(Looper.getMainLooper())

    private var mDownloadSearchCallback: DownloadSearchCallback? = null

    private val mList: List<DownloadInfo>?
    private var resultList: List<DownloadInfo>? = null
    private val mSearchKey: String

    @Suppress("unused")
    private var mDownloadManager: DownloadManager? = null

    constructor(mList: List<DownloadInfo>?, searchKey: String) {
        this.mList = mList
        this.mSearchKey = searchKey
    }

    constructor(mList: List<DownloadInfo>?, downloadManager: DownloadManager?) {
        this.mList = mList
        this.mSearchKey = ""
        mDownloadManager = downloadManager
    }

    fun setDownloadSearchingListener(downloadSearchCallback: DownloadSearchCallback?) {
        mDownloadSearchCallback = downloadSearchCallback
    }

    fun executeSearching() {
        service.execute {
            resultList = searchingInBackground()
            handler.post {
                mDownloadSearchCallback?.onDownloadSearchSuccess(resultList)
            }
        }
    }

    fun executeFilterAndSort(id: Int) {
        service.execute {
            resultList = when (id) {
                R.id.download_done -> filterDownloadState(DownloadInfo.STATE_FINISH)
                R.id.not_started -> filterDownloadState(DownloadInfo.STATE_NONE)
                R.id.waiting -> filterDownloadState(DownloadInfo.STATE_WAIT)
                R.id.downloading -> filterDownloadState(DownloadInfo.STATE_DOWNLOAD)
                R.id.failed -> filterDownloadState(DownloadInfo.STATE_FAILED)
                R.id.sort_by_gallery_id_asc,
                R.id.sort_by_gallery_id_desc,
                R.id.sort_by_create_time_asc,
                R.id.sort_by_create_time_desc,
                R.id.sort_by_rating_asc,
                R.id.sort_by_rating_desc,
                R.id.sort_by_name_asc,
                R.id.sort_by_name_desc,
                R.id.sort_by_file_size_asc,
                R.id.sort_by_file_size_desc -> sortByType(id)
                R.id.all, R.id.sort_by_default -> mList
                else -> mList
            }

            handler.post {
                mDownloadSearchCallback?.onDownloadSearchSuccess(resultList)
            }
        }
    }

    private fun sortByType(type: Int): List<DownloadInfo> {
        if (mList == null) {
            return ArrayList()
        }
        val arr = mList.toTypedArray()

        // If sorting by file size, calculate all file sizes first
        if (type == R.id.sort_by_file_size_asc || type == R.id.sort_by_file_size_desc) {
            for (info in arr) {
                if (info.fileSize < 0) { // Not yet calculated
                    info.fileSize = calculateDownloadDirSize(info)
                }
            }
        }

        val n = arr.size
        // Sub-array sizes are 1, 2, 4, 8...
        var i = 1
        while (i < n) {
            // Partition the array
            var left = 0
            var mid = left + i - 1
            var right = mid + i
            // Merge in pairs of sub-arrays of size i
            while (right < n) {
                merge(arr, left, mid, right, type)
                left = right + 1
                mid = left + i - 1
                right = mid + i
            }
            // Handle remaining unmerged portions
            if (left < n && mid < n) {
                merge(arr, left, mid, n - 1, type)
            }
            i += i
        }
        return Arrays.asList(*arr)
    }

    private fun filterDownloadState(state: Int): List<DownloadInfo> {
        val list = ArrayList<DownloadInfo>()
        if (mList == null) {
            return list
        }
        for (info in mList) {
            if (info.state == state) {
                list.add(info)
            }
        }
        return list
    }

    protected fun searchingInBackground(): List<DownloadInfo>? {
        if (mDownloadSearchCallback == null) {
            return ArrayList()
        }
        if (mSearchKey.isEmpty()) {
            return mList
        }
        if (mList == null) {
            return ArrayList()
        }
        val cache = ArrayList<DownloadInfo>()

        for (info in mList) {
            if (EhUtils.judgeSuitableTitle(info, mSearchKey)) {
                cache.add(info)
            } else if (matchTag(mSearchKey, info)) {
                cache.add(info)
            }
        }

        return cache
    }

    private fun matchTag(searchKey: String, info: DownloadInfo): Boolean {
        val currentList = info.tgList
        if (currentList == null || currentList.isEmpty()) {
            info.tgList = searchTagList(info.gid)
        }
        val tagList = info.tgList ?: return false

        val searchTags = searchKey.split("  ")

        var result = true
        for (searchTag in searchTags) {
            if (!tagList.contains(searchTag)) {
                result = false
                break
            }
        }

        return result
    }

    private fun searchTagList(gid: Long): ArrayList<String>? {
        val tags: GalleryTags = EhDB.queryGalleryTags(gid) ?: return null

        val tagList = ArrayList<String>()

        tagList.addAll(parserList("artist", tags.artist))
        tagList.addAll(parserList("rows", tags.rows))
        tagList.addAll(parserList("cosplayer", tags.cosplayer))
        tagList.addAll(parserList("character", tags.character))
        tagList.addAll(parserList("female", tags.female))
        tagList.addAll(parserList("group", tags.group))
        tagList.addAll(parserList("language", tags.language))
        tagList.addAll(parserList("male", tags.male))
        tagList.addAll(parserList("misc", tags.misc))
        tagList.addAll(parserList("mixed", tags.mixed))
        tagList.addAll(parserList("other", tags.other))
        tagList.addAll(parserList("parody", tags.parody))
        tagList.addAll(parserList("reclass", tags.reclass))

        return tagList
    }

    private fun parserList(name: String?, content: String?): ArrayList<String> {
        if (name == null || content == null) {
            return ArrayList()
        }
        val list = ArrayList<String>()
        val tagNames = content.split(",")
        for (s in tagNames) {
            list.add("$name:$s")
        }
        return list
    }

    /**
     * Calculate the total size of the download directory
     */
    private fun calculateDownloadDirSize(info: DownloadInfo): Long {
        return try {
            val downloadDir = SpiderDen.getGalleryDownloadDir(info)
            if (downloadDir == null || !downloadDir.isDirectory) {
                return -1
            }
            calculateFolderSize(downloadDir)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Recursively calculate folder size
     */
    private fun calculateFolderSize(folder: UniFile): Long {
        var totalSize: Long = 0
        val files = folder.listFiles() ?: return 0

        for (file in files) {
            if (file.isFile) {
                val fileSize = file.length()
                if (fileSize > 0) {
                    totalSize += fileSize
                }
            } else if (file.isDirectory) {
                totalSize += calculateFolderSize(file) // Recursively calculate sub-folders
            }
        }

        return totalSize
    }

    companion object {
        // Merge function: merge two sorted arrays
        // arr[left..mid] represents one array, arr[mid+1..right] represents another
        private fun merge(arr: Array<DownloadInfo>, left: Int, mid: Int, right: Int, sortType: Int) {
            val a = Array<DownloadInfo?>(right - left + 1) { null }
            var i = left
            var j = mid + 1
            var k = 0
            while (i <= mid && j <= right) {
                when (sortType) {
                    R.id.sort_by_gallery_id_asc -> {
                        if (arr[i].gid < arr[j].gid) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_gallery_id_desc -> {
                        if (arr[i].gid > arr[j].gid) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_create_time_asc -> {
                        if (arr[i].time < arr[j].time) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_create_time_desc -> {
                        if (arr[i].time > arr[j].time) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_rating_asc -> {
                        if (arr[i].rating < arr[j].rating) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_rating_desc -> {
                        if (arr[i].rating > arr[j].rating) a[k++] = arr[i++] else a[k++] = arr[j++]
                    }
                    R.id.sort_by_name_asc -> {
                        val titleI = arr[i].title
                        val titleJ = arr[j].title
                        when {
                            titleI == null && titleJ == null -> a[k++] = arr[i++]
                            titleI == null -> a[k++] = arr[j++]
                            titleJ == null -> a[k++] = arr[i++]
                            titleI.compareTo(titleJ, ignoreCase = true) < 0 -> a[k++] = arr[i++]
                            else -> a[k++] = arr[j++]
                        }
                    }
                    R.id.sort_by_name_desc -> {
                        val titleI = arr[i].title
                        val titleJ = arr[j].title
                        when {
                            titleI == null && titleJ == null -> a[k++] = arr[i++]
                            titleI == null -> a[k++] = arr[j++]
                            titleJ == null -> a[k++] = arr[i++]
                            titleI.compareTo(titleJ, ignoreCase = true) > 0 -> a[k++] = arr[i++]
                            else -> a[k++] = arr[j++]
                        }
                    }
                    R.id.sort_by_file_size_asc -> {
                        when {
                            arr[i].fileSize < 0 && arr[j].fileSize < 0 -> a[k++] = arr[i++]
                            arr[i].fileSize < 0 -> a[k++] = arr[j++]
                            arr[j].fileSize < 0 -> a[k++] = arr[i++]
                            arr[i].fileSize < arr[j].fileSize -> a[k++] = arr[i++]
                            else -> a[k++] = arr[j++]
                        }
                    }
                    R.id.sort_by_file_size_desc -> {
                        when {
                            arr[i].fileSize < 0 && arr[j].fileSize < 0 -> a[k++] = arr[i++]
                            arr[i].fileSize < 0 -> a[k++] = arr[j++]
                            arr[j].fileSize < 0 -> a[k++] = arr[i++]
                            arr[i].fileSize > arr[j].fileSize -> a[k++] = arr[i++]
                            else -> a[k++] = arr[j++]
                        }
                    }
                }
            }
            while (i <= mid) a[k++] = arr[i++]
            while (j <= right) a[k++] = arr[j++]
            // Copy temp array back to original
            var idx = left
            for (x in 0 until k) {
                arr[idx++] = a[x]!!
            }
        }
    }
}
