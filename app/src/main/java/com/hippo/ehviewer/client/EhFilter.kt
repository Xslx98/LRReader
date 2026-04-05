/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client

import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.Filter

class EhFilter private constructor() {

    private val mTitleFilterList = mutableListOf<Filter>()
    private val mUploaderFilterList = mutableListOf<Filter>()
    private val mTagFilterList = mutableListOf<Filter>()
    private val mTagNamespaceFilterList = mutableListOf<Filter>()

    val titleFilterList: List<Filter> get() = mTitleFilterList
    val uploaderFilterList: List<Filter> get() = mUploaderFilterList
    val tagFilterList: List<Filter> get() = mTagFilterList
    val tagNamespaceFilterList: List<Filter> get() = mTagNamespaceFilterList

    init {
        val list = EhDB.getAllFilter()
        for (filter in list) {
            when (filter.mode) {
                MODE_TITLE -> {
                    filter.text = filter.text?.lowercase()
                    mTitleFilterList.add(filter)
                }
                MODE_UPLOADER -> {
                    mUploaderFilterList.add(filter)
                }
                MODE_TAG -> {
                    filter.text = filter.text?.lowercase()
                    mTagFilterList.add(filter)
                }
                MODE_TAG_NAMESPACE -> {
                    filter.text = filter.text?.lowercase()
                    mTagNamespaceFilterList.add(filter)
                }
                else -> Log.d(TAG, "Unknown mode: ${filter.mode}")
            }
        }
    }

    @Synchronized
    fun addFilter(filter: Filter) {
        // enable filter by default before it is added to database
        filter.enable = true
        EhDB.addFilter(filter)

        when (filter.mode) {
            MODE_TITLE -> {
                filter.text = filter.text?.lowercase()
                mTitleFilterList.add(filter)
            }
            MODE_UPLOADER -> {
                mUploaderFilterList.add(filter)
            }
            MODE_TAG -> {
                filter.text = filter.text?.lowercase()
                mTagFilterList.add(filter)
            }
            MODE_TAG_NAMESPACE -> {
                filter.text = filter.text?.lowercase()
                mTagNamespaceFilterList.add(filter)
            }
            else -> Log.d(TAG, "Unknown mode: ${filter.mode}")
        }
    }

    @Synchronized
    fun triggerFilter(filter: Filter) {
        EhDB.triggerFilter(filter)
    }

    @Synchronized
    fun deleteFilter(filter: Filter) {
        EhDB.deleteFilter(filter)

        when (filter.mode) {
            MODE_TITLE -> mTitleFilterList.remove(filter)
            MODE_UPLOADER -> mUploaderFilterList.remove(filter)
            MODE_TAG -> mTagFilterList.remove(filter)
            MODE_TAG_NAMESPACE -> mTagNamespaceFilterList.remove(filter)
            else -> Log.d(TAG, "Unknown mode: ${filter.mode}")
        }
    }

    @Synchronized
    fun needTags(): Boolean =
        mTagFilterList.isNotEmpty() || mTagNamespaceFilterList.isNotEmpty()

    @Synchronized
    fun filterTitle(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val title = info.title
        val filters = mTitleFilterList
        if (title != null && filters.isNotEmpty()) {
            for (filter in filters) {
                if (filter.enable == true && filter.text != null &&
                    title.lowercase().contains(filter.text!!)
                ) {
                    return false
                }
            }
        }
        return true
    }

    @Synchronized
    fun filterUploader(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val uploader = info.uploader
        val filters = mUploaderFilterList
        if (uploader != null && filters.isNotEmpty()) {
            for (filter in filters) {
                if (filter.enable == true && uploader == filter.text) {
                    return false
                }
            }
        }
        return true
    }

    private fun matchTag(tag: String?, filter: String?): Boolean {
        if (tag == null || filter == null) return false

        val tagNamespace: String?
        val tagName: String
        val index = tag.indexOf(':')
        if (index < 0) {
            tagNamespace = null
            tagName = tag
        } else {
            tagNamespace = tag.substring(0, index)
            tagName = tag.substring(index + 1)
        }

        val filterNamespace: String?
        val filterName: String
        val filterIndex = filter.indexOf(':')
        if (filterIndex < 0) {
            filterNamespace = null
            filterName = filter
        } else {
            filterNamespace = filter.substring(0, filterIndex)
            filterName = filter.substring(filterIndex + 1)
        }

        if (tagNamespace != null && filterNamespace != null && tagNamespace != filterNamespace) {
            return false
        }
        return tagName == filterName
    }

    @Synchronized
    fun filterTag(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val tags = info.simpleTags
        val filters = mTagFilterList
        if (tags != null && filters.isNotEmpty()) {
            for (tag in tags) {
                for (filter in filters) {
                    if (filter.enable == true && matchTag(tag, filter.text)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun matchTagNamespace(tag: String?, filter: String?): Boolean {
        if (tag == null || filter == null) return false

        val index = tag.indexOf(':')
        if (index >= 0) {
            val tagNamespace = tag.substring(0, index)
            return tagNamespace == filter
        }
        return false
    }

    @Synchronized
    fun filterTagNamespace(info: GalleryInfo?): Boolean {
        if (info == null) return false

        val tags = info.simpleTags
        val filters = mTagNamespaceFilterList
        if (tags != null && filters.isNotEmpty()) {
            for (tag in tags) {
                for (filter in filters) {
                    if (filter.enable == true && matchTagNamespace(tag, filter.text)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "EhFilter"

        const val MODE_TITLE = 0
        const val MODE_UPLOADER = 1
        const val MODE_TAG = 2
        const val MODE_TAG_NAMESPACE = 3

        @JvmStatic
        @Volatile
        private var sInstance: EhFilter? = null

        @JvmStatic
        fun getInstance(): EhFilter {
            if (sInstance == null) {
                sInstance = EhFilter()
            }
            return sInstance!!
        }
    }
}
