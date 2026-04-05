/*
 * Copyright 2019 Hippo Seven
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

class GalleryCommentList : Parcelable {

    @JvmField var comments: Array<GalleryComment>?
    @JvmField var hasMore: Boolean

    constructor(comments: Array<GalleryComment>?, hasMore: Boolean) {
        this.comments = comments
        this.hasMore = hasMore
    }

    @Suppress("FunctionNaming")
    fun DeleteComment(num: Int) {
        val old = comments ?: return
        val commentArray = arrayOfNulls<GalleryComment>(old.size - 1)
        var j = 0
        for (i in old.indices) {
            if (i != num) {
                commentArray[j] = old[i]
                j++
            }
        }
        @Suppress("UNCHECKED_CAST")
        comments = commentArray as Array<GalleryComment>
    }

    private constructor(parcel: Parcel) {
        val array = parcel.readParcelableArray(GalleryComment::class.java.classLoader)
        comments = if (array != null) {
            Arrays.copyOf(array, array.size, Array<GalleryComment>::class.java)
        } else {
            null
        }
        hasMore = parcel.readByte().toInt() != 0
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelableArray(comments, flags)
        dest.writeByte(if (hasMore) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<GalleryCommentList> {
        override fun createFromParcel(parcel: Parcel): GalleryCommentList = GalleryCommentList(parcel)
        override fun newArray(size: Int): Array<GalleryCommentList?> = arrayOfNulls(size)
    }
}
