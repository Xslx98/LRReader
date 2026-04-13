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
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
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

@SuppressLint("InflateParams")
class SearchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : EasyRecyclerView(context, attrs, defStyle),
    CompoundButton.OnCheckedChangeListener,
    View.OnClickListener,
    ImageSearchLayout.Helper {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    private var searchMode = SEARCH_MODE_NORMAL
    private var enableAdvance = false

    private val normalView: View
    private val sortBySpinner: Spinner
    private val sortOrderSpinner: Spinner
    private val sortByValues: Array<String>
    private val sortOrderValues: Array<String>

    private val advanceView: View
    private val tableAdvanceSearch: AdvanceSearchTable

    private val imageView: ImageSearchLayout

    private val actionView: View
    private val action: TextView

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

        // Force disable advance search (not used for LANraragi)
        enableAdvance = false

        // Create advance view (kept for layout compatibility, not shown)
        advanceView = inflater.inflate(R.layout.search_advance, null)
        tableAdvanceSearch = advanceView.findViewById(R.id.search_advance_search_table)

        // Create image view (not used for LANraragi)
        imageView = inflater.inflate(R.layout.search_image, null) as ImageSearchLayout
        imageView.setHelper(this)

        // Create action view — hidden for LANraragi (no image search)
        actionView = inflater.inflate(R.layout.search_action, null)
        action = actionView.findViewById(R.id.action)
        action.setOnClickListener(this)
        actionView.visibility = View.GONE
    }

    fun setHelper(helper: Helper?) {
        this.helper = helper
    }

    fun scrollSearchContainerToTop() {
        searchLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    fun setImageUri(imageUri: Uri?) {
        imageView.setImageUri(imageUri)
    }

    fun setNormalSearchMode(id: Int) {
        // No-op for LANraragi (no E-Hentai search modes)
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

    override fun onSelectImage() {
        helper?.onSelectImage()
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchSaveInstanceState(container)
        normalView.saveHierarchyState(container)
        advanceView.saveHierarchyState(container)
        imageView.saveHierarchyState(container)
        actionView.saveHierarchyState(container)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchRestoreInstanceState(container)
        normalView.restoreHierarchyState(container)
        advanceView.restoreHierarchyState(container)
        imageView.restoreHierarchyState(container)
        actionView.restoreHierarchyState(container)
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putInt(STATE_KEY_SEARCH_MODE, searchMode)
        state.putBoolean(STATE_KEY_ENABLE_ADVANCE, enableAdvance)
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable(STATE_KEY_SUPER))
            searchMode = state.getInt(STATE_KEY_SEARCH_MODE)
            enableAdvance = state.getBoolean(STATE_KEY_ENABLE_ADVANCE)
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        // No-op for LANraragi
    }

    @Throws(EhException::class)
    fun formatListUrlBuilder(urlBuilder: ListUrlBuilder, query: String?) {
        urlBuilder.reset()
        // LANraragi: always simple keyword search
        urlBuilder.mode = ListUrlBuilder.MODE_NORMAL
        urlBuilder.keyword = query
    }

    fun setSearchMode(@SearchMode searchMode: Int, animation: Boolean) {
        if (this.searchMode != searchMode) {
            val oldItemCount = searchAdapter.itemCount
            this.searchMode = searchMode
            val newItemCount = searchAdapter.itemCount

            if (animation) {
                searchAdapter.notifyItemRangeRemoved(0, oldItemCount - 1)
                searchAdapter.notifyItemRangeInserted(0, newItemCount - 1)
            } else {
                // Intentional non-animated fallback; animated branch uses granular notifications
                @Suppress("NotifyDataSetChanged")
                searchAdapter.notifyDataSetChanged()
            }

            helper?.onChangeSearchMode()
        }
    }

    fun toggleSearchMode() {
        val oldItemCount = searchAdapter.itemCount

        searchMode++
        if (searchMode > SEARCH_MODE_IMAGE) {
            searchMode = SEARCH_MODE_NORMAL
        }

        val newItemCount = searchAdapter.itemCount

        searchAdapter.notifyItemRangeRemoved(0, oldItemCount - 1)
        searchAdapter.notifyItemRangeInserted(0, newItemCount - 1)

        // Update action text
        val resId = when (searchMode) {
            SEARCH_MODE_IMAGE -> R.string.keyword_search
            else -> R.string.image_search
        }
        action.setText(resId)

        helper?.onChangeSearchMode()
    }

    override fun onClick(v: View) {
        if (action === v) {
            toggleSearchMode()
        }
    }

    private inner class SimpleHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private inner class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int {
            var count = SEARCH_ITEM_COUNT_ARRAY[searchMode]
            if (searchMode == SEARCH_MODE_NORMAL && !enableAdvance) {
                count--
            }
            return count
        }

        override fun getItemViewType(position: Int): Int {
            var type = SEARCH_ITEM_TYPE[searchMode][position]
            if (searchMode == SEARCH_MODE_NORMAL && position == 1 && !enableAdvance) {
                type = ITEM_TYPE_ACTION
            }
            return type
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View

            if (viewType == ITEM_TYPE_ACTION) {
                ViewUtils.removeFromParent(actionView)
                actionView.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val resId = when (searchMode) {
                    SEARCH_MODE_IMAGE -> R.string.keyword_search
                    else -> R.string.image_search
                }
                action.setText(resId)
                view = actionView
            } else {
                view = inflater.inflate(R.layout.search_category, parent, false)
                val title = view.findViewById<TextView>(R.id.category_title)
                val content = view.findViewById<FrameLayout>(R.id.category_content)
                when (viewType) {
                    ITEM_TYPE_NORMAL -> {
                        title.setText(R.string.search_normal)
                        ViewUtils.removeFromParent(normalView)
                        content.addView(normalView)
                    }
                    ITEM_TYPE_NORMAL_ADVANCE -> {
                        title.setText(R.string.search_advance)
                        ViewUtils.removeFromParent(advanceView)
                        content.addView(advanceView)
                    }
                    ITEM_TYPE_IMAGE -> {
                        title.setText(R.string.search_image)
                        ViewUtils.removeFromParent(imageView)
                        content.addView(imageView)
                    }
                }
            }

            return SimpleHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Empty, bind view in create view
        }
    }

    interface Helper {
        fun onChangeSearchMode()
        fun onSelectImage()
        fun onSortChanged()
    }

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION,
        AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY_GETTER)
    annotation class SearchMode

    companion object {
        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_SEARCH_MODE = "search_mode"
        private const val STATE_KEY_ENABLE_ADVANCE = "enable_advance"

        const val SEARCH_MODE_NORMAL = 0
        const val SEARCH_MODE_IMAGE = 1

        private const val ITEM_TYPE_NORMAL = 0
        private const val ITEM_TYPE_NORMAL_ADVANCE = 1
        private const val ITEM_TYPE_IMAGE = 2
        private const val ITEM_TYPE_ACTION = 3

        private val SEARCH_ITEM_COUNT_ARRAY = intArrayOf(3, 2)

        private val SEARCH_ITEM_TYPE = arrayOf(
            intArrayOf(ITEM_TYPE_NORMAL, ITEM_TYPE_NORMAL_ADVANCE, ITEM_TYPE_ACTION), // SEARCH_MODE_NORMAL
            intArrayOf(ITEM_TYPE_IMAGE, ITEM_TYPE_ACTION) // SEARCH_MODE_IMAGE
        )
    }
}
