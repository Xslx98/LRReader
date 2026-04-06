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
import java.util.Arrays

class GalleryDetail : GalleryInfo {

    @JvmField
    var apiUid: Long = -1L

    @JvmField
    var apiKey: String? = null

    @JvmField
    var torrentCount: Int = 0

    @JvmField
    var torrentUrl: String? = null

    @JvmField
    var archiveUrl: String? = null

    @JvmField
    var parent: String? = null

    @JvmField
    var visible: String? = null

    @JvmField
    var language: String? = null

    @JvmField
    var size: String? = null

    @JvmField
    var SpiderInfoPages: Int = 0

    @JvmField
    var favoriteCount: Int = 0

    @JvmField
    var isFavorited: Boolean = false

    @JvmField
    var ratingCount: Int = 0

    @JvmField
    var tags: Array<GalleryTagGroup>? = null

    @JvmField
    var previewPages: Int = 0

    @JvmField
    var SpiderInfoPreviewPages: Int = 0

    @JvmField
    var previewSet: PreviewSet? = null

    @JvmField
    var SpiderInfoPreviewSet: PreviewSet? = null

    constructor()

    protected constructor(`in`: Parcel) : super(`in`) {
        torrentCount = `in`.readInt()
        torrentUrl = `in`.readString()
        archiveUrl = `in`.readString()
        parent = `in`.readString()
        visible = `in`.readString()
        language = `in`.readString()
        size = `in`.readString()
        pages = `in`.readInt()
        SpiderInfoPages = `in`.readInt()
        favoriteCount = `in`.readInt()
        isFavorited = `in`.readByte().toInt() != 0
        ratingCount = `in`.readInt()
        val array = `in`.readParcelableArray(GalleryTagGroup::class.java.classLoader)
        tags = if (array != null) {
            Arrays.copyOf(array, array.size, Array<GalleryTagGroup>::class.java)
        } else {
            null
        }
        previewPages = `in`.readInt()
        SpiderInfoPreviewPages = `in`.readInt()
        previewSet = `in`.readParcelable(PreviewSet::class.java.classLoader)
        SpiderInfoPreviewSet = `in`.readParcelable(PreviewSet::class.java.classLoader)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeInt(torrentCount)
        dest.writeString(torrentUrl)
        dest.writeString(archiveUrl)
        dest.writeString(parent)
        dest.writeString(visible)
        dest.writeString(language)
        dest.writeString(size)
        dest.writeInt(pages)
        dest.writeInt(SpiderInfoPages)
        dest.writeInt(favoriteCount)
        dest.writeByte(if (isFavorited) 1.toByte() else 0.toByte())
        dest.writeInt(ratingCount)
        dest.writeParcelableArray(tags, flags)
        dest.writeInt(previewPages)
        dest.writeInt(SpiderInfoPreviewPages)
        dest.writeParcelable(previewSet, flags)
        dest.writeParcelable(SpiderInfoPreviewSet, flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GalleryDetail> = object : Parcelable.Creator<GalleryDetail> {
            override fun createFromParcel(source: Parcel): GalleryDetail = GalleryDetail(source)
            override fun newArray(size: Int): Array<GalleryDetail?> = arrayOfNulls(size)
        }
    }
}
