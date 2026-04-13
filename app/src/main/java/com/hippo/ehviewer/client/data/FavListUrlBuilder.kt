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

package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import com.hippo.ehviewer.client.LRRUrl
import com.hippo.ehviewer.widget.GalleryInfoContentHelper
import com.hippo.network.UrlBuilder
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_FIRST_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_LAST_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_NEXT_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.GOTO_PREV_PAGE
import com.hippo.widget.ContentLayout.ContentHelper.TYPE_SOMEWHERE
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.regex.Pattern

class FavListUrlBuilder : Parcelable {

    var index: Int = 0
    var keyword: String? = null
    var favCat: Int = FAV_CAT_ALL

    val isLocalFavCat: Boolean
        get() = favCat == FAV_CAT_LOCAL

    constructor()

    private constructor(parcel: Parcel) {
        index = parcel.readInt()
        keyword = parcel.readString()
        favCat = parcel.readInt()
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

    fun build(pageAction: Int, helper: GalleryInfoContentHelper): String? {
        return when (pageAction) {
            GOTO_PREV_PAGE -> helper.prevHref
            GOTO_NEXT_PAGE, TYPE_SOMEWHERE -> helper.nextHref
            GOTO_LAST_PAGE -> helper.lastHref
            else -> helper.firstHref
        }
    }

    fun build(): String {
        val ub = UrlBuilder(LRRUrl.getFavoritesUrl())
        if (isValidFavCat(favCat)) {
            ub.addQuery("favcat", favCat.toString())
        }
        if (!TextUtils.isEmpty(keyword)) {
            try {
                ub.addQuery("f_search", URLEncoder.encode(keyword, "UTF-8"))
                // Name
                ub.addQuery("sn", "on")
                // Tags
                ub.addQuery("st", "on")
                // Note
                ub.addQuery("sf", "on")
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, "Can't URLEncoder.encode $keyword")
            }
        }
        if (index > 0) {
            ub.addQuery("page", index.toString())
        }
        return ub.build()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(index)
        dest.writeString(keyword)
        dest.writeInt(favCat)
    }

    companion object {
        private val PATTERN_SEEK_DATE = Pattern.compile("seek=(\\d+)-(\\d+)-(\\d+)")
        private val PATTERN_JUMP_NODE = Pattern.compile("jump=(\\d)[ymwd]")
        private val TAG = FavListUrlBuilder::class.java.simpleName

        const val FAV_CAT_ALL = -1
        const val FAV_CAT_LOCAL = -2

        @JvmStatic
        fun isValidFavCat(favCat: Int): Boolean = favCat in 0..9

        @JvmField
        val CREATOR: Parcelable.Creator<FavListUrlBuilder> = object : Parcelable.Creator<FavListUrlBuilder> {
            override fun createFromParcel(source: Parcel): FavListUrlBuilder = FavListUrlBuilder(source)
            override fun newArray(size: Int): Array<FavListUrlBuilder?> = arrayOfNulls(size)
        }
    }
}
