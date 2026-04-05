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

class GalleryComment : Parcelable {

    // 0 for uploader comment. can't vote
    @JvmField var id: Long = 0
    @JvmField var score: Int = 0
    @JvmField var editable: Boolean = false
    @JvmField var voteUpAble: Boolean = false
    @JvmField var voteUpEd: Boolean = false
    @JvmField var voteDownAble: Boolean = false
    @JvmField var voteDownEd: Boolean = false
    @JvmField var voteState: String? = null
    @JvmField var time: Long = 0
    @JvmField var user: String? = null
    @JvmField var comment: String? = null
    @JvmField var lastEdited: Long = 0

    constructor()

    private constructor(parcel: Parcel) {
        id = parcel.readLong()
        score = parcel.readInt()
        editable = parcel.readByte().toInt() != 0
        voteUpAble = parcel.readByte().toInt() != 0
        voteUpEd = parcel.readByte().toInt() != 0
        voteDownAble = parcel.readByte().toInt() != 0
        voteDownEd = parcel.readByte().toInt() != 0
        voteState = parcel.readString()
        time = parcel.readLong()
        user = parcel.readString()
        comment = parcel.readString()
        lastEdited = parcel.readLong()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeInt(score)
        dest.writeByte(if (editable) 1 else 0)
        dest.writeByte(if (voteUpAble) 1 else 0)
        dest.writeByte(if (voteUpEd) 1 else 0)
        dest.writeByte(if (voteDownAble) 1 else 0)
        dest.writeByte(if (voteDownEd) 1 else 0)
        dest.writeString(voteState)
        dest.writeLong(time)
        dest.writeString(user)
        dest.writeString(comment)
        dest.writeLong(lastEdited)
    }

    companion object CREATOR : Parcelable.Creator<GalleryComment> {
        override fun createFromParcel(source: Parcel): GalleryComment = GalleryComment(source)
        override fun newArray(size: Int): Array<GalleryComment?> = arrayOfNulls(size)
    }
}
