/*
 * Copyright 2017 Hippo Seven
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
package com.hippo.ehviewer.dao

import android.os.Parcel
import android.os.Parcelable

/**
 * In-memory view over the favorite subsystem of an
 * `ARCHIVE_LOCAL_STATE` row.
 *
 * Post-L1-4 this class is no longer a Room `@Entity` — the persisted
 * state lives on [ArchiveLocalState] (FAVORITE_TIME column is non-null
 * when the archive is in local favorites). The repository layer
 * translates between this view and the unified row.
 */
class LocalFavoriteInfo() : Parcelable {

    @JvmField
    var arcid: String = ""

    @JvmField
    var title: String? = null

    @JvmField
    var thumb: String? = null

    @JvmField
    var rating: Float = 0f

    @JvmField
    var simpleLanguage: String? = null

    @JvmField
    var serverProfileId: Long = 0

    @JvmField
    var time: Long = 0

    /** Display tags; populated by the mapper from Archive.flatTags. */
    @JvmField
    var simpleTags: Array<String>? = null

    private constructor(`in`: Parcel) : this() {
        arcid = `in`.readString() ?: ""
        title = `in`.readString()
        thumb = `in`.readString()
        rating = `in`.readFloat()
        simpleLanguage = `in`.readString()
        serverProfileId = `in`.readLong()
        time = `in`.readLong()
        simpleTags = `in`.createStringArray()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        dest.writeString(thumb)
        dest.writeFloat(rating)
        dest.writeString(simpleLanguage)
        dest.writeLong(serverProfileId)
        dest.writeLong(time)
        dest.writeStringArray(simpleTags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LocalFavoriteInfo> = object : Parcelable.Creator<LocalFavoriteInfo> {
            override fun createFromParcel(source: Parcel): LocalFavoriteInfo = LocalFavoriteInfo(source)
            override fun newArray(size: Int): Array<LocalFavoriteInfo?> = arrayOfNulls(size)
        }
    }
}
