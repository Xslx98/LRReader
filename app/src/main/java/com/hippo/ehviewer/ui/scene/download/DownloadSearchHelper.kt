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

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.client.LRRUtils
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.util.DrawableManager
import com.hippo.widget.SearchBarMover

/**
 * Manages the search dialog, search mode state, and category filter spinner
 * for DownloadsScene. Extracted from DownloadsScene (W11-3).
 */
internal class DownloadSearchHelper(private val callback: Callback) {

    interface Callback {
        val ehContext: Context?
        val viewModel: DownloadsViewModel
        fun getResources(): android.content.res.Resources
        fun getString(resId: Int): String
        fun updateForLabel()
        fun updateView()
        /** The SearchBar.Helper + SearchBarMover.Helper delegate (the Scene itself). */
        val searchBarHelper: SearchBar.Helper
        val searchBarMoverHelper: SearchBarMover.Helper
    }

    var searchDialog: AlertDialog? = null
        private set
    var searchBar: SearchBar? = null
        private set
    var searchBarMover: SearchBarMover? = null
        private set
    var searchMode: Boolean = false

    var searchKey: String?
        get() = callback.viewModel.searchKey.value
        set(value) { callback.viewModel.setSearchKey(value) }

    var searching: Boolean
        get() = callback.viewModel.searching.value
        set(value) { callback.viewModel.setSearching(value) }

    fun gotoSearch(context: Context) {
        if (searchDialog != null) {
            searchDialog!!.show()
            return
        }
        val layoutInflater = LayoutInflater.from(context)

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)

        val linearLayout = layoutInflater.inflate(R.layout.download_search_dialog, null) as LinearLayout
        val bar = linearLayout.findViewById<SearchBar>(R.id.download_search_bar)
        searchBar = bar
        bar.setHelper(callback.searchBarHelper)
        bar.setIsComeFromDownload(true)
        bar.setEditTextHint(R.string.download_search_hint)
        bar.setLeftDrawable(drawable)
        bar.setText(searchKey)
        if (!searchKey.isNullOrEmpty()) {
            bar.setTitle(searchKey)
            bar.cursorToEnd()
        } else {
            bar.setTitle(R.string.download_search_hint)
        }

        bar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24))
        searchBarMover = SearchBarMover(callback.searchBarMoverHelper, bar)
        searchDialog = AlertDialog.Builder(context)
            .setMessage(R.string.download_search_gallery)
            .setView(linearLayout)
            .setCancelable(true)
            .setOnDismissListener { onSearchDialogDismiss(it) }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                searchKey = null
                bar.setText(null)
                bar.setTitle(null as String?)
                bar.applySearch(true)
                dialog.dismiss()
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                bar.applySearch(true)
                dialog.dismiss()
            }.show()
    }

    private fun onSearchDialogDismiss(@Suppress("UNUSED_PARAMETER") dialog: DialogInterface) {
        searchMode = false
    }

    fun enterSearchMode(animation: Boolean) {
        val bar = searchBar
        if (searchMode || bar == null || searchBarMover == null) {
            return
        }
        searchMode = true
        bar.setState(SearchBar.STATE_SEARCH_LIST, animation)
        searchBarMover!!.returnSearchBarPosition(animation)
    }

    fun startSearching() {
        if (searchMode) {
            searchMode = false
            searchBar?.setTitle(searchKey)
            searchBar?.setState(SearchBar.STATE_NORMAL)
        }

        searchDialog!!.dismiss()

        callback.updateForLabel()

        callback.viewModel.startSearching(searchKey ?: "")
    }

    fun onApplySearch(query: String) {
        searchKey = query
        searchBar?.hideKeyBoard()
        searching = true
        startSearching()
    }

    fun onSearchEditTextBackPressed() {
        if (searchMode) {
            searchMode = false
        }
        searchBar?.setState(SearchBar.STATE_NORMAL, true)
    }

    fun initCategorySpinner(spinner: Spinner, context: Context) {
        val categoryList = ArrayList<String>()
        categoryList.add(callback.getString(R.string.category_all))
        categoryList.add(LRRUtils.getCategory(EhConfig.DOUJINSHI)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.MANGA)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.ARTIST_CG)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.GAME_CG)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.WESTERN)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.NON_H)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.IMAGE_SET)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.COSPLAY)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.ASIAN_PORN)!!.uppercase(java.util.Locale.ROOT))
        categoryList.add(LRRUtils.getCategory(EhConfig.MISC)!!.uppercase(java.util.Locale.ROOT))

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
                    else -> LRRUtils.ALL_CATEGORY
                }
                if (selectedCategory != callback.viewModel.selectedCategory.value) {
                    callback.viewModel.setSelectedCategory(selectedCategory)
                    callback.viewModel.filterByCategory()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        spinner.setSelection(0)
    }
}
