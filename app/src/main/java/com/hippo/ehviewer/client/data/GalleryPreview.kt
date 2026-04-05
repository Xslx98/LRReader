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
import com.hippo.widget.LoadImageView

class GalleryPreview : Parcelable {

    @JvmField var imageKey: String? = null
    @JvmField var imageUrl: String? = null
    @JvmField var pageUrl: String? = null
    @JvmField var position: Int = 0
    @JvmField var offsetX: Int = Int.MIN_VALUE
    @JvmField var offsetY: Int = Int.MIN_VALUE
    @JvmField var clipWidth: Int = Int.MIN_VALUE
    @JvmField var clipHeight: Int = Int.MIN_VALUE

    fun getPosition(): Int = position

    fun load(view: LoadImageView) {
        view.setClip(offsetX, offsetY, clipWidth, clipHeight)
        view.load(imageKey, imageUrl)
    }

    constructor()

    private constructor(parcel: Parcel) {
        imageUrl = parcel.readString()
        pageUrl = parcel.readString()
        position = parcel.readInt()
        offsetX = parcel.readInt()
        offsetY = parcel.readInt()
        clipWidth = parcel.readInt()
        clipHeight = parcel.readInt()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(imageUrl)
        dest.writeString(pageUrl)
        dest.writeInt(position)
        dest.writeInt(offsetX)
        dest.writeInt(offsetY)
        dest.writeInt(clipWidth)
        dest.writeInt(clipHeight)
    }

    companion object CREATOR : Parcelable.Creator<GalleryPreview> {
        override fun createFromParcel(source: Parcel): GalleryPreview = GalleryPreview(source)
        override fun newArray(size: Int): Array<GalleryPreview?> = arrayOfNulls(size)
    }
}
