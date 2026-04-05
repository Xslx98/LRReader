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

class GalleryApiInfo : Parcelable {

    @JvmField var gid: Long = 0
    @JvmField var token: String? = null
    @JvmField var archiverKey: String? = null
    @JvmField var title: String? = null
    @JvmField var titleJpn: String? = null
    @JvmField var category: Int = 0
    @JvmField var thumb: String? = null
    @JvmField var uploader: String? = null
    @JvmField var posted: Long = 0
    @JvmField var filecount: Int = 0
    @JvmField var filesize: Long = 0
    @JvmField var expunged: Boolean = false
    @JvmField var rating: Float = 0f
    @JvmField var torrentcount: Int = 0
    @JvmField var tags: Array<String>? = null

    constructor()

    private constructor(parcel: Parcel) {
        gid = parcel.readLong()
        token = parcel.readString()
        archiverKey = parcel.readString()
        title = parcel.readString()
        titleJpn = parcel.readString()
        category = parcel.readInt()
        thumb = parcel.readString()
        uploader = parcel.readString()
        posted = parcel.readLong()
        filecount = parcel.readInt()
        filesize = parcel.readLong()
        expunged = parcel.readByte().toInt() != 0
        rating = parcel.readFloat()
        torrentcount = parcel.readInt()
        tags = parcel.createStringArray()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(gid)
        dest.writeString(token)
        dest.writeString(archiverKey)
        dest.writeString(title)
        dest.writeString(titleJpn)
        dest.writeInt(category)
        dest.writeString(thumb)
        dest.writeString(uploader)
        dest.writeLong(posted)
        dest.writeInt(filecount)
        dest.writeLong(filesize)
        dest.writeByte(if (expunged) 1 else 0)
        dest.writeFloat(rating)
        dest.writeInt(torrentcount)
        dest.writeStringArray(tags)
    }

    companion object CREATOR : Parcelable.Creator<GalleryApiInfo> {
        override fun createFromParcel(source: Parcel): GalleryApiInfo = GalleryApiInfo(source)
        override fun newArray(size: Int): Array<GalleryApiInfo?> = arrayOfNulls(size)
    }
}
