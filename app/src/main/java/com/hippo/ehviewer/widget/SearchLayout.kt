/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.lib.yorozuya.ViewUtils

/**
 * The search-options page shown behind the search bar. LANraragi only
 * supports plain keyword search, so this is a single card with the
 * sort-by / sort-order spinners (the EhViewer-era advance-search and
 * image-search pages are gone).
 */
@SuppressLint("InflateParams")
class SearchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : EasyRecyclerView(context, attrs, defStyle) {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    private val normalView: View
    private val sortBySpinner: Spinner
    private val sortOrderSpinner: Spinner
    private val sortByValues: Array<String>
    private val sortOrderValues: Array<String>

    private val searchLayoutManager: LinearLayoutManager
    private val searchAdapter: SearchAdapter

    private var helper: Helper? = null

    init {
        val resources = context.resources

        searchLayoutManager = LinearLayoutManager(context)
        searchAdapter = SearchAdapter()
        layoutManager = searchLayoutManager
        adapter = searchAdapter
        setHasFixedSize(true)
        clipToPadding = false
        val interval = resources.getDimensionPixelOffset(R.dimen.search_layout_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        addItemDecoration(decoration)
        decoration.applyPaddings(this)

        // Create normal view with LANraragi sort options
        normalView = inflater.inflate(R.layout.search_normal, null)

        sortBySpinner = normalView.findViewById(R.id.spinner_sort_by)
        sortOrderSpinner = normalView.findViewById(R.id.spinner_sort_order)

        // Load sort values arrays
        sortByValues = resources.getStringArray(R.array.lrr_sort_by_values)
        sortOrderValues = resources.getStringArray(R.array.lrr_sort_order_values)

        // Populate sort by spinner
        val sortByAdapter = ArrayAdapter.createFromResource(
            context, R.array.lrr_sort_by_entries, android.R.layout.simple_spinner_item
        )
        sortByAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortBySpinner.adapter = sortByAdapter

        // Populate sort order spinner
        val sortOrderAdapter = ArrayAdapter.createFromResource(
            context, R.array.lrr_sort_order_entries, android.R.layout.simple_spinner_item
        )
        sortOrderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortOrderSpinner.adapter = sortOrderAdapter

        // Default: date_added descending
        sortBySpinner.setSelection(1) // date_added
        sortOrderSpinner.setSelection(1) // desc

        // Listen for sort changes to auto-refresh gallery
        val sortListener = object : AdapterView.OnItemSelectedListener {
            private var callCount = 0 // Skip first 2 calls (initial layout)

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (callCount < 2) {
                    callCount++
                    return
                }
                helper?.onSortChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sortBySpinner.onItemSelectedListener = sortListener
        sortOrderSpinner.onItemSelectedListener = sortListener
    }

    fun setHelper(helper: Helper?) {
        this.helper = helper
    }

    fun scrollSearchContainerToTop() {
        searchLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    /**
     * Get the selected sort-by value for LANraragi search API.
     * @return API sort key like "title", "date_added", "lastread", etc.
     */
    val sortBy: String
        get() {
            val pos = sortBySpinner.selectedItemPosition
            return if (pos in sortByValues.indices) {
                sortByValues[pos]
            } else {
                "title"
            }
        }

    /**
     * Get the selected sort order for LANraragi search API.
     * @return "asc" or "desc"
     */
    val sortOrder: String
        get() {
            val pos = sortOrderSpinner.selectedItemPosition
            return if (pos in sortOrderValues.indices) {
                sortOrderValues[pos]
            } else {
                "asc"
            }
        }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchSaveInstanceState(container)
        normalView.saveHierarchyState(container)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchRestoreInstanceState(container)
        normalView.restoreHierarchyState(container)
    }

    @Throws(EhException::class)
    fun formatListUrlBuilder(urlBuilder: ListUrlBuilder, query: String?) {
        urlBuilder.reset()
        // LANraragi: always simple keyword search
        urlBuilder.mode = ListUrlBuilder.MODE_NORMAL
        urlBuilder.keyword = query
    }

    private inner class SimpleHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private inner class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = inflater.inflate(R.layout.search_category, parent, false)
            view.findViewById<TextView>(R.id.category_title).setText(R.string.search_normal)
            ViewUtils.removeFromParent(normalView)
            view.findViewById<FrameLayout>(R.id.category_content).addView(normalView)
            return SimpleHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Empty, bind view in create view
        }
    }

    interface Helper {
        fun onSortChanged()
    }
}
