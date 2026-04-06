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
package com.hippo.ehviewer.ui.scene.download

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.hippo.ehviewer.R
import com.hippo.ehviewer.callBack.DownloadSearchCallback
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.sync.DownloadListInfosExecutor
import java.util.Locale

/**
 * Handles category filtering and sort/filter execution for downloads,
 * extracted from DownloadsScene to reduce its line count.
 *
 * Also implements [DownloadSearchCallback] to receive filter/sort and
 * search results from [DownloadListInfosExecutor].
 */
class DownloadFilterHelper(private val mCallback: Callback) : DownloadSearchCallback {

    /**
     * Callback interface so the helper can interact with its host
     * (DownloadsScene) without a direct dependency.
     */
    interface Callback {
        fun getContext(): Context?
        fun getString(resId: Int): String
        fun getList(): MutableList<DownloadInfo>?
        fun getBackList(): List<DownloadInfo>?
        fun getDownloadManager(): DownloadManager?
        fun setList(list: MutableList<DownloadInfo>)
        fun isAdded(): Boolean
        fun isSearching(): Boolean
        fun setSearching(searching: Boolean)
        fun showProgress()
        fun hideProgress()
        fun updateAdapter()
        fun updateTitle()
        fun updatePaginationIndicator()
        fun updateView()
        fun queryUnreadSpiderInfo()
        fun notifyDataSetChanged()
    }

    private var mSelectedCategory = EhUtils.ALL_CATEGORY

    // -------------------------------------------------------------------------
    // Public API — category spinner
    // -------------------------------------------------------------------------

    /**
     * Initializes the category spinner with all known categories.
     * Call from onCreateView3.
     */
    fun initCategorySpinner(spinner: Spinner, context: Context) {
        val categoryList = ArrayList<String>()
        categoryList.add(mCallback.getString(R.string.category_all))
        categoryList.add(EhUtils.getCategory(EhConfig.DOUJINSHI)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.MANGA)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.ARTIST_CG)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.GAME_CG)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.WESTERN)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.NON_H)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.IMAGE_SET)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.COSPLAY)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.ASIAN_PORN)!!.uppercase(Locale.ROOT))
        categoryList.add(EhUtils.getCategory(EhConfig.MISC)!!.uppercase(Locale.ROOT))

        val categoryAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, categoryList)
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = categoryAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCategory = when (position) {
                    1 -> EhConfig.DOUJINSHI
                    2 -> EhConfig.MANGA
                    3 -> EhConfig.ARTIST_CG
                    4 -> EhConfig.GAME_CG
                    5 -> EhConfig.WESTERN
                    6 -> EhConfig.NON_H
                    7 -> EhConfig.IMAGE_SET
                    8 -> EhConfig.COSPLAY
                    9 -> EhConfig.ASIAN_PORN
                    10 -> EhConfig.MISC
                    else -> EhUtils.ALL_CATEGORY
                }
                if (selectedCategory != mSelectedCategory) {
                    mSelectedCategory = selectedCategory
                    filterByCategory()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        spinner.setSelection(0)
    }

    // -------------------------------------------------------------------------
    // Public API — filter and sort
    // -------------------------------------------------------------------------

    /**
     * Executes a filter-and-sort operation for the given menu item [id].
     */
    fun gotoFilterAndSort(id: Int) {
        mCallback.showProgress()

        val executor = DownloadListInfosExecutor(mCallback.getBackList(), mCallback.getDownloadManager())
        executor.setDownloadSearchingListener(this)
        executor.executeFilterAndSort(id)
    }

    /**
     * Executes a search on the current download list.
     */
    fun startSearching(searchKey: String) {
        mCallback.showProgress()

        val list = mCallback.getList()
        val executor = DownloadListInfosExecutor(list, searchKey)
        executor.setDownloadSearchingListener(this)
        executor.executeSearching()
    }

    // -------------------------------------------------------------------------
    // DownloadSearchCallback implementation
    // -------------------------------------------------------------------------

    override fun onDownloadSearchSuccess(list: MutableList<DownloadInfo>) {
        if (!mCallback.isAdded()) return
        mCallback.setList(list)
        mCallback.updateAdapter()
        mCallback.hideProgress()
        mCallback.setSearching(false)
        mCallback.queryUnreadSpiderInfo()
    }

    override fun onDownloadListHandleSuccess(list: MutableList<DownloadInfo>) {
        if (!mCallback.isAdded()) return
        mCallback.setList(list)
        mCallback.updateAdapter()
        mCallback.hideProgress()
        mCallback.queryUnreadSpiderInfo()
    }

    override fun onDownloadSearchFailed(list: MutableList<DownloadInfo>) {
        val context = mCallback.getContext()
        if (context != null) {
            android.widget.Toast.makeText(
                context, R.string.download_searching_failed, android.widget.Toast.LENGTH_LONG
            ).show()
        }
        mCallback.setList(list)
        mCallback.updateAdapter()
        mCallback.hideProgress()
        mCallback.setSearching(false)
        mCallback.queryUnreadSpiderInfo()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    @SuppressLint("NotifyDataSetChanged")
    private fun filterByCategory() {
        val backList = mCallback.getBackList() ?: return
        if (mSelectedCategory == EhUtils.ALL_CATEGORY) {
            mCallback.setList(ArrayList(backList))
        } else {
            val filtered = ArrayList<DownloadInfo>()
            for (info in backList) {
                if (info.category == mSelectedCategory) {
                    filtered.add(info)
                }
            }
            mCallback.setList(filtered)
        }
        mCallback.notifyDataSetChanged()
        mCallback.updateTitle()
        mCallback.updatePaginationIndicator()
        mCallback.updateView()
        mCallback.queryUnreadSpiderInfo()
    }
}
