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

package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import androidx.annotation.IntDef
import com.hippo.ehviewer.client.EhConfig
import com.hippo.ehviewer.client.LRRUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.widget.AdvanceSearchTable
import com.hippo.ehviewer.widget.GalleryInfoContentHelper
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.network.UrlBuilder
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_FIRST_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_LAST_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_NEXT_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_PREV_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.TYPE_SOMEWHERE
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

class ListUrlBuilder : Cloneable, Parcelable {

    @IntDef(
        MODE_NORMAL, MODE_UPLOADER, MODE_TAG, MODE_FILTER,
        MODE_WHATS_HOT, MODE_IMAGE_SEARCH, MODE_SUBSCRIPTION, MODE_TOP_LIST
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Mode

    @JvmField
    @Mode
    var mode: Int = MODE_NORMAL

    @JvmField
    var pageIndex: Int = 0

    @JvmField
    var category: Int = EhUtils.NONE

    @JvmField
    var keyword: String? = null

    @JvmField
    var follow: String? = null

    @JvmField
    var advanceSearch: Int = -1

    @JvmField
    var minRating: Int = -1

    @JvmField
    var pageFrom: Int = -1

    @JvmField
    var pageTo: Int = -1

    @JvmField
    var imagePath: String? = null

    @JvmField
    var useSimilarityScan: Boolean = false

    @JvmField
    var onlySearchCovers: Boolean = false

    @JvmField
    var showExpunged: Boolean = false

    constructor()

    @Suppress("WrongConstant")
    private constructor(parcel: Parcel) {
        mode = parcel.readInt()
        pageIndex = parcel.readInt()
        category = parcel.readInt()
        keyword = parcel.readString()
        advanceSearch = parcel.readInt()
        minRating = parcel.readInt()
        pageFrom = parcel.readInt()
        pageTo = parcel.readInt()
        imagePath = parcel.readString()
        useSimilarityScan = parcel.readByte() != 0.toByte()
        onlySearchCovers = parcel.readByte() != 0.toByte()
        showExpunged = parcel.readByte() != 0.toByte()
    }

    /**
     * Make this ListUrlBuilder point to homepage
     */
    fun reset() {
        mode = MODE_NORMAL
        pageIndex = 0
        category = EhUtils.NONE
        keyword = null
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
        imagePath = null
        useSimilarityScan = false
        onlySearchCovers = false
        showExpunged = false
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
    fun setFollow(value: String?) { follow = value }
    fun getAdvanceSearch(): Int = advanceSearch
    fun setAdvanceSearch(value: Int) { advanceSearch = value }
    fun getMinRating(): Int = minRating
    fun setMinRating(value: Int) { minRating = value }
    fun getPageFrom(): Int = pageFrom
    fun setPageFrom(value: Int) { pageFrom = value }
    fun getPageTo(): Int = pageTo
    fun setPageTo(value: Int) { pageTo = value }
    fun getImagePath(): String? = imagePath
    fun setImagePath(value: String?) { imagePath = value }
    fun isUseSimilarityScan(): Boolean = useSimilarityScan
    fun setUseSimilarityScan(value: Boolean) { useSimilarityScan = value }
    fun isOnlySearchCovers(): Boolean = onlySearchCovers
    fun setOnlySearchCovers(value: Boolean) { onlySearchCovers = value }
    fun isShowExpunged(): Boolean = showExpunged
    fun setShowExpunged(value: Boolean) { showExpunged = value }

    /**
     * Make them the same
     * @param lub The template
     */
    fun set(lub: ListUrlBuilder) {
        mode = lub.mode
        pageIndex = lub.pageIndex
        category = lub.category
        keyword = lub.keyword
        follow = lub.follow
        advanceSearch = lub.advanceSearch
        minRating = lub.minRating
        pageFrom = lub.pageFrom
        pageTo = lub.pageTo
        imagePath = lub.imagePath
        useSimilarityScan = lub.useSimilarityScan
        onlySearchCovers = lub.onlySearchCovers
        showExpunged = lub.showExpunged
    }

    fun set(q: QuickSearch) {
        mode = q.mode
        category = q.category
        keyword = q.keyword
        advanceSearch = q.advanceSearch
        minRating = q.minRating
        pageFrom = q.pageFrom
        pageTo = q.pageTo
        imagePath = null
        useSimilarityScan = false
        onlySearchCovers = false
        showExpunged = false
    }

    fun set(q: String?, newMode: Int) {
        mode = newMode
        category = -1
        keyword = q
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
        imagePath = null
        useSimilarityScan = false
        onlySearchCovers = false
        showExpunged = false
    }

    fun set(q: String?) {
        mode = MODE_TAG
        category = -1
        keyword = q
        advanceSearch = -1
        minRating = -1
        pageFrom = -1
        pageTo = -1
        imagePath = null
        useSimilarityScan = false
        onlySearchCovers = false
        showExpunged = false
    }

    fun toQuickSearch(): QuickSearch {
        return QuickSearch().apply {
            mode = this@ListUrlBuilder.mode
            category = this@ListUrlBuilder.category
            keyword = this@ListUrlBuilder.keyword
            advanceSearch = this@ListUrlBuilder.advanceSearch
            minRating = this@ListUrlBuilder.minRating
            pageFrom = this@ListUrlBuilder.pageFrom
            pageTo = this@ListUrlBuilder.pageTo
        }
    }

    fun equalsQuickSearch(q: QuickSearch?): Boolean {
        if (q == null) return false
        return q.mode == mode &&
            q.category == category &&
            StringUtils.equals(q.keyword, keyword) &&
            q.advanceSearch == advanceSearch &&
            q.minRating == minRating &&
            q.pageFrom == pageFrom &&
            q.pageTo == pageTo
    }

    fun equalKeyWord(other: String?): Boolean {
        return keyword != null && keyword == other
    }

    /**
     * @param query xxx=yyy&mmm=nnn
     */
    // EH-LEGACY: pagination for URL builder not implemented
    fun setQuery(query: String?) {
        reset()

        if (TextUtils.isEmpty(query)) return

        val querys = StringUtils.split(query, '&')
        var parsedCategory = 0
        var parsedKeyword: String? = null
        var enableAdvanceSearch = false
        var parsedAdvanceSearch = 0
        var enableMinRating = false
        var parsedMinRating = -1
        var enablePage = false
        var parsedPageFrom = -1
        var parsedPageTo = -1

        for (str in querys) {
            val index = str.indexOf('=')
            if (index < 0) continue
            val key = str.substring(0, index)
            val value = str.substring(index + 1)

            when (key) {
                "f_cats" -> {
                    val cats = NumberUtils.parseIntSafely(value, EhConfig.ALL_CATEGORY)
                    parsedCategory = parsedCategory or (cats.inv() and EhConfig.ALL_CATEGORY)
                }
                "f_doujinshi" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.DOUJINSHI
                "f_manga" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.MANGA
                "f_artistcg" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.ARTIST_CG
                "f_gamecg" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.GAME_CG
                "f_western" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.WESTERN
                "f_non-h" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.NON_H
                "f_imageset" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.IMAGE_SET
                "f_cosplay" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.COSPLAY
                "f_asianporn" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.ASIAN_PORN
                "f_misc" -> if ("1" == value) parsedCategory = parsedCategory or EhConfig.MISC
                "f_search" -> {
                    try {
                        parsedKeyword = URLDecoder.decode(value, "utf-8")
                    } catch (e: UnsupportedEncodingException) {
                        Log.d(TAG, "Decode search keyword", e)
                    } catch (e: IllegalArgumentException) {
                        Log.d(TAG, "Decode search keyword", e)
                    }
                }
                "advsearch" -> if ("1" == value) enableAdvanceSearch = true
                "f_sname" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SNAME
                "f_stags" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.STAGS
                "f_sdesc" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SDESC
                "f_storr" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.STORR
                "f_sto" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.STO
                "f_sdt1" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SDT1
                "f_sdt2" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SDT2
                "f_sh" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SH
                "f_sfl" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SFL
                "f_sfu" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SFU
                "f_sft" -> if ("on" == value) parsedAdvanceSearch = parsedAdvanceSearch or AdvanceSearchTable.SFT
                "f_sr" -> if ("on" == value) enableMinRating = true
                "f_srdd" -> parsedMinRating = NumberUtils.parseIntSafely(value, -1)
                "f_sp" -> if ("on" == value) enablePage = true
                "f_spf" -> parsedPageFrom = NumberUtils.parseIntSafely(value, -1)
                "f_spt" -> parsedPageTo = NumberUtils.parseIntSafely(value, -1)
            }
        }

        category = parsedCategory
        keyword = parsedKeyword
        if (enableAdvanceSearch) {
            advanceSearch = parsedAdvanceSearch
            minRating = if (enableMinRating) parsedMinRating else -1
            if (enablePage) {
                pageFrom = parsedPageFrom
                pageTo = parsedPageTo
            } else {
                pageFrom = -1
                pageTo = -1
            }
        } else {
            advanceSearch = -1
        }
    }

    fun build(pageAction: Int, helper: GalleryInfoContentHelper): String? {
        return when (pageAction) {
            GOTO_PREV_PAGE -> helper.prevHref
            GOTO_NEXT_PAGE, TYPE_SOMEWHERE -> helper.nextHref
            GOTO_LAST_PAGE -> helper.lastHref
            else -> helper.firstHref // GOTO_FIRST_PAGE and default
        }
    }

    fun jumpHrefBuild(urlOld: String, appendParam: String): String {
        val seekM = PATTERN_SEEK_DATE.matcher(urlOld)
        val jumpM = PATTERN_JUMP_NODE.matcher(urlOld)

        return when {
            seekM.find() -> urlOld.replace(seekM.group(0)!!, appendParam)
            jumpM.find() -> urlOld.replace(jumpM.group(0)!!, appendParam)
            else -> "$urlOld&$appendParam"
        }
    }

    fun build(): String {
        return when (mode) {
            MODE_UPLOADER -> buildString {
                append(LRRUrl.getHost())
                append("uploader/")
                try {
                    append(URLEncoder.encode(keyword, "UTF-8"))
                } catch (e: UnsupportedEncodingException) {
                    Log.d(TAG, "Encode uploader keyword", e)
                }
                if (pageIndex != 0) {
                    append('/').append(pageIndex)
                }
            }

            MODE_TAG -> buildString {
                append(LRRUrl.getHost())
                append("tag/")
                try {
                    append(URLEncoder.encode(keyword, "UTF-8"))
                } catch (e: UnsupportedEncodingException) {
                    Log.d(TAG, "Encode tag keyword", e)
                }
                if (pageIndex != 0) {
                    append('/').append(pageIndex)
                }
            }

            MODE_FILTER -> buildString {
                append(LRRUrl.getHost())
                append("?")
                if (pageIndex != 0) {
                    append("page=").append(pageIndex).append('&')
                }
                append("f_search=")
                try {
                    append(URLEncoder.encode(keyword, "UTF-8"))
                } catch (e: UnsupportedEncodingException) {
                    Log.d(TAG, "Encode filter keyword", e)
                }
            }

            MODE_WHATS_HOT -> LRRUrl.getPopularUrl()

            MODE_IMAGE_SEARCH -> LRRUrl.getImageSearchUrl()

            MODE_TOP_LIST -> buildString {
                append(LRRUrl.getTopListUrl())
                append("?")
                append(follow)
                if (pageIndex != 0) {
                    if (pageIndex in 1..199) {
                        append("&p=")
                        append(pageIndex)
                    } else {
                        // Invalid page range — return dummy URL
                        clear()
                        append("127.0.0.1:8888")
                    }
                }
            }

            else -> {
                // MODE_NORMAL, MODE_SUBSCRIPTION
                val url = if (mode == MODE_NORMAL) LRRUrl.getHost() else LRRUrl.getWatchedUrl()
                val ub = UrlBuilder(url)
                if (category != EhUtils.NONE) {
                    ub.addQuery("f_cats", category.inv() and EhConfig.ALL_CATEGORY)
                }
                // Search key
                keyword?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    try {
                        ub.addQuery("f_search", URLEncoder.encode(keyword, "UTF-8"))
                    } catch (e: UnsupportedEncodingException) {
                        Log.d(TAG, "Encode search keyword", e)
                    }
                }
                // Page index
                if (pageIndex != 0) {
                    ub.addQuery("page", pageIndex)
                }
                // Advance search
                if (advanceSearch != -1) {
                    ub.addQuery("advsearch", "1")
                    if (advanceSearch and AdvanceSearchTable.SNAME != 0) ub.addQuery("f_sname", "on")
                    if (advanceSearch and AdvanceSearchTable.STAGS != 0) ub.addQuery("f_stags", "on")
                    if (advanceSearch and AdvanceSearchTable.SDESC != 0) ub.addQuery("f_sdesc", "on")
                    if (advanceSearch and AdvanceSearchTable.STORR != 0) ub.addQuery("f_storr", "on")
                    if (advanceSearch and AdvanceSearchTable.STO != 0) ub.addQuery("f_sto", "on")
                    if (advanceSearch and AdvanceSearchTable.SDT1 != 0) ub.addQuery("f_sdt1", "on")
                    if (advanceSearch and AdvanceSearchTable.SDT2 != 0) ub.addQuery("f_sdt2", "on")
                    if (advanceSearch and AdvanceSearchTable.SH != 0) ub.addQuery("f_sh", "on")
                    if (advanceSearch and AdvanceSearchTable.SFL != 0) ub.addQuery("f_sfl", "on")
                    if (advanceSearch and AdvanceSearchTable.SFU != 0) ub.addQuery("f_sfu", "on")
                    if (advanceSearch and AdvanceSearchTable.SFT != 0) ub.addQuery("f_sft", "on")
                    // Min star rating
                    if (minRating != -1) {
                        ub.addQuery("f_sr", "on")
                        ub.addQuery("f_srdd", minRating)
                    }
                    // Pages
                    if (pageFrom != -1 || pageTo != -1) {
                        ub.addQuery("f_sp", "on")
                        ub.addQuery("f_spf", if (pageFrom != -1) pageFrom.toString() else "")
                        ub.addQuery("f_spt", if (pageTo != -1) pageTo.toString() else "")
                    }
                }
                ub.build()
            }
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(mode)
        dest.writeInt(pageIndex)
        dest.writeInt(category)
        dest.writeString(keyword)
        dest.writeInt(advanceSearch)
        dest.writeInt(minRating)
        dest.writeInt(pageFrom)
        dest.writeInt(pageTo)
        dest.writeString(imagePath)
        dest.writeByte(if (useSimilarityScan) 1 else 0)
        dest.writeByte(if (onlySearchCovers) 1 else 0)
        dest.writeByte(if (showExpunged) 1 else 0)
    }

    companion object {
        private const val TAG = "ListUrlBuilder"
        private val PATTERN_SEEK_DATE = Pattern.compile("seek=(\\d+)-(\\d+)-(\\d+)")
        private val PATTERN_JUMP_NODE = Pattern.compile("jump=(\\d)[ymwd]")

        // Mode constants
        const val MODE_NORMAL = 0x0
        const val MODE_UPLOADER = 0x1
        const val MODE_TAG = 0x2
        const val MODE_WHATS_HOT = 0x3
        const val MODE_IMAGE_SEARCH = 0x4
        const val MODE_SUBSCRIPTION = 0x5
        const val MODE_FILTER = 0x6
        const val MODE_TOP_LIST = 0x7

        const val DEFAULT_ADVANCE = AdvanceSearchTable.SNAME or AdvanceSearchTable.STAGS
        const val DEFAULT_MIN_RATING = 2

        @JvmField
        val CREATOR: Parcelable.Creator<ListUrlBuilder> = object : Parcelable.Creator<ListUrlBuilder> {
            override fun createFromParcel(source: Parcel): ListUrlBuilder = ListUrlBuilder(source)
            override fun newArray(size: Int): Array<ListUrlBuilder?> = arrayOfNulls(size)
        }
    }
}
