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

// EH-LEGACY: URL field was considered but never needed
class GalleryTagGroup : Parcelable {

    @JvmField var groupName: String? = null
    private val mTagList: ArrayList<String>

    constructor() {
        mTagList = ArrayList()
    }

    private constructor(parcel: Parcel) {
        groupName = parcel.readString()
        mTagList = parcel.createStringArrayList() ?: ArrayList()
    }

    fun addTag(tag: String) {
        mTagList.add(tag)
    }

    fun size(): Int = mTagList.size

    fun getTagAt(position: Int): String = mTagList[position]

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(groupName)
        dest.writeStringList(mTagList)
    }

    companion object CREATOR : Parcelable.Creator<GalleryTagGroup> {
        override fun createFromParcel(source: Parcel): GalleryTagGroup = GalleryTagGroup(source)
        override fun newArray(size: Int): Array<GalleryTagGroup?> = arrayOfNulls(size)
    }
}
