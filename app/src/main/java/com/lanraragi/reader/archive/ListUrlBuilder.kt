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

package com.lanraragi.reader.archive

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.IntDef
import com.lanraragi.reader.dao.QuickSearch

class ListUrlBuilder : Cloneable, Parcelable {

    @IntDef(
        MODE_NORMAL, MODE_UPLOADER, MODE_TAG, MODE_FILTER,
        MODE_WHATS_HOT, MODE_SUBSCRIPTION, MODE_TOP_LIST
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Mode

    @JvmField
    @Mode
    var mode: Int = MODE_NORMAL

    @JvmField
    var pageIndex: Int = 0

    @JvmField
    var category: Int = LRRUtils.NONE

    @JvmField
    var keyword: String? = null

    /**
     * LANraragi category id (e.g. "SET_1704939135"), sent as `/api/search?category=`.
     * Null = no category filter. Independent of [keyword], which stays plain filter text
     * (spec 2026-09-15: display and transport are separate).
     */
    @JvmField
    var categoryId: String? = null

    /**
     * Display-only name for [categoryId]. May be null (rows migrated from the legacy
     * "category:<id>" keyword protocol) or stale after a server-side rename; never
     * part of equality.
     */
    @JvmField
    var categoryName: String? = null

    @JvmField
    var advanceSearch: Int = -1

    @JvmField
    var minRating: Int = -1

    @JvmField
    var pageFrom: Int = -1

    @JvmField
    var pageTo: Int = -1

    constructor()

    private constructor(parcel: Parcel) {
        mode = parcel.readInt()
        pageIndex = parcel.readInt()
        category = parcel.readInt()
        keyword = parcel.readString()
        categoryId = parcel.readString()
        categoryName = parcel.readString()
        advanceSearch = parcel.readInt()
        minRating = parcel.readInt()
        pageFrom = parcel.readInt()
        pageTo = parcel.readInt()
    }

    /**
     * Make this ListUrlBuilder point to homepage
     */
    fun reset() {
        mode = MODE_NORMAL
        pageIndex = 0
        category = LRRUtils.NONE
        keyword = null
        categoryId = null
        categoryName = null
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
    }

    /**
     * Apply a typed query while staying inside the current category: everything else
     * resets like a fresh [MODE_NORMAL] search, but [categoryId] / [categoryName] survive
     * so the server keeps filtering within the category (`category` + `filter` together).
     * With no category set this is equivalent to `reset(); keyword = q`.
     */
    fun setKeywordKeepingCategory(q: String?) {
        val id = categoryId
        val name = categoryName
        reset()
        mode = MODE_NORMAL
        categoryId = id
        categoryName = name
        keyword = q
    }

    public override fun clone(): ListUrlBuilder {
        try {
            return super.clone() as ListUrlBuilder
        } catch (e: CloneNotSupportedException) {
            throw IllegalStateException(e)
        }
    }

    // Getters/setters for Java callers that use getMode()/setMode() style
    fun getMode(): Int = mode
    fun setMode(@Mode value: Int) { mode = value }
    fun getPageIndex(): Int = pageIndex
    fun setPageIndex(value: Int) { pageIndex = value }
    fun getCategory(): Int = category
    fun setCategory(value: Int) { category = value }
    fun getKeyword(): String? = keyword
    fun setKeyword(value: String?) { keyword = value }
    fun getCategoryId(): String? = categoryId
    fun setCategoryId(value: String?) { categoryId = value }
    fun getCategoryName(): String? = categoryName
    fun setCategoryName(value: String?) { categoryName = value }
    fun getAdvanceSearch(): Int = advanceSearch
    fun setAdvanceSearch(value: Int) { advanceSearch = value }
    fun getMinRating(): Int = minRating
    fun setMinRating(value: Int) { minRating = value }
    fun getPageFrom(): Int = pageFrom
    fun setPageFrom(value: Int) { pageFrom = value }
    fun getPageTo(): Int = pageTo
    fun setPageTo(value: Int) { pageTo = value }

    /**
     * Make them the same
     * @param lub The template
     */
    fun set(lub: ListUrlBuilder) {
        mode = lub.mode
        pageIndex = lub.pageIndex
        category = lub.category
        keyword = lub.keyword
        categoryId = lub.categoryId
        categoryName = lub.categoryName
        advanceSearch = lub.advanceSearch
        minRating = lub.minRating
        pageFrom = lub.pageFrom
        pageTo = lub.pageTo
    }

    fun set(q: QuickSearch) {
        mode = q.mode
        category = q.category
        keyword = q.keyword
        categoryId = q.categoryId
        categoryName = q.categoryName
        advanceSearch = q.advanceSearch
        minRating = q.minRating
        pageFrom = q.pageFrom
        pageTo = q.pageTo
    }

    fun set(q: String?, newMode: Int) {
        mode = newMode
        category = -1
        keyword = q
        categoryId = null
        categoryName = null
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
    }

    fun set(q: String?) {
        mode = MODE_TAG
        category = -1
        keyword = q
        categoryId = null
        categoryName = null
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
    }

    fun toQuickSearch(): QuickSearch {
        return QuickSearch().apply {
            mode = this@ListUrlBuilder.mode
            category = this@ListUrlBuilder.category
            keyword = this@ListUrlBuilder.keyword
            categoryId = this@ListUrlBuilder.categoryId
            categoryName = this@ListUrlBuilder.categoryName
            advanceSearch = this@ListUrlBuilder.advanceSearch
            minRating = this@ListUrlBuilder.minRating
            pageFrom = this@ListUrlBuilder.pageFrom
            pageTo = this@ListUrlBuilder.pageTo
        }
    }

    fun equalsQuickSearch(q: QuickSearch?): Boolean {
        if (q == null) return false
        // categoryName is display-only (may lag a server rename) and deliberately not compared.
        return q.mode == mode &&
            q.category == category &&
            q.keyword.orEmpty() == keyword.orEmpty() &&
            q.categoryId.orEmpty() == categoryId.orEmpty() &&
            q.advanceSearch == advanceSearch &&
            q.minRating == minRating &&
            q.pageFrom == pageFrom &&
            q.pageTo == pageTo
    }

    fun equalKeyWord(other: String?): Boolean {
        return keyword != null && keyword == other
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(mode)
        dest.writeInt(pageIndex)
        dest.writeInt(category)
        dest.writeString(keyword)
        dest.writeString(categoryId)
        dest.writeString(categoryName)
        dest.writeInt(advanceSearch)
        dest.writeInt(minRating)
        dest.writeInt(pageFrom)
        dest.writeInt(pageTo)
    }

    companion object {
        // Mode constants
        const val MODE_NORMAL = 0x0
        const val MODE_UPLOADER = 0x1
        const val MODE_TAG = 0x2
        const val MODE_WHATS_HOT = 0x3
        // 0x4 was MODE_IMAGE_SEARCH (EhViewer legacy); values are persisted
        // in QuickSearch rows, so the remaining constants keep their values.
        const val MODE_SUBSCRIPTION = 0x5
        const val MODE_FILTER = 0x6
        const val MODE_TOP_LIST = 0x7


        @JvmField
        val CREATOR: Parcelable.Creator<ListUrlBuilder> = object : Parcelable.Creator<ListUrlBuilder> {
            override fun createFromParcel(source: Parcel): ListUrlBuilder = ListUrlBuilder(source)
            override fun newArray(size: Int): Array<ListUrlBuilder?> = arrayOfNulls(size)
        }
    }
}
