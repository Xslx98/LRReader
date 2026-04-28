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
 * In-memory view over the history subsystem of an `ARCHIVE_LOCAL_STATE`
 * row.
 *
 * Post-L1-4 this class is no longer a Room `@Entity` — the persisted
 * state lives on [ArchiveLocalState] (HISTORY_TIME / HISTORY_MODE
 * columns are non-null when the archive is in reading history). The
 * repository layer translates between this view and the unified row.
 */
class HistoryInfo() : Parcelable {

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
    var mode: Int = 0

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
        mode = `in`.readInt()
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
        dest.writeInt(mode)
        dest.writeLong(time)
        dest.writeStringArray(simpleTags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HistoryInfo> = object : Parcelable.Creator<HistoryInfo> {
            override fun createFromParcel(source: Parcel): HistoryInfo = HistoryInfo(source)
            override fun newArray(size: Int): Array<HistoryInfo?> = arrayOfNulls(size)
        }
    }
}
