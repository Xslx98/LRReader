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
import com.lanraragi.reader.domain.TagGroup

/**
 * In-memory model for the gallery detail view, built from an
 * [com.lanraragi.reader.client.api.LRRArchive] response.
 *
 * W36-11: standalone Parcelable. Used to inherit GalleryInfoEntity for
 * the shared display columns (arcid/title/thumb/rating/etc.); the
 * remaining handful of fields are now declared locally so this class
 * carries only what its UI consumers actually read.
 */
class GalleryDetail() : Parcelable {

    // ── Display fields shared with the gallery list view ──

    @JvmField var arcid: String = ""

    @JvmField var title: String? = null

    @JvmField var thumb: String? = null

    @JvmField var rating: Float = 0f

    @JvmField var simpleLanguage: String? = null

    @JvmField var serverProfileId: Long = 0

    // ── Cross-screen state used by the detail UI ──

    /** Total page count (LRR pagecount). Read by header binder + reader. */
    @JvmField var pages: Int = 0

    /** Last reader page (1-indexed; 0 = unread). Mutable on read-progress events. */
    @JvmField var progress: Int = 0

    /** True after a successful rating submission this session. */
    @JvmField var rated: Boolean = false

    /** Display string for the uploader chip — null on LRR (no uploader concept). */
    @JvmField var uploader: String? = null

    /** Local favorite slot label, set by viewmodel after lookup. */
    @JvmField var favoriteName: String? = null

    /** True iff the archive is in any local favorite. Used by header heart icon. */
    @JvmField var isFavorited: Boolean = false

    /**
     * Legacy EH gid; LRR builds always set 0. Retained because the rating-
     * result Bundle still carries a KEY_GID extra and removing it requires
     * a wider sweep of the Scene result-handling path.
     */
    @JvmField var gid: Long = 0

    // ── Detail-only fields (no list-view counterpart) ──

    @JvmField var torrentCount: Int = 0

    @JvmField var language: String? = null

    @JvmField var size: String? = null

    @JvmField var SpiderInfoPages: Int = 0

    @JvmField var favoriteCount: Int = 0

    @JvmField var ratingCount: Int = 0

    @JvmField var tags: List<TagGroup>? = null

    @JvmField var previewPages: Int = 0

    @JvmField var SpiderInfoPreviewPages: Int = 0

    // ── Parcelable ──

    private constructor(`in`: Parcel) : this() {
        arcid = `in`.readString() ?: ""
        title = `in`.readString()
        thumb = `in`.readString()
        rating = `in`.readFloat()
        simpleLanguage = `in`.readString()
        serverProfileId = `in`.readLong()
        pages = `in`.readInt()
        progress = `in`.readInt()
        rated = `in`.readByte().toInt() != 0
        uploader = `in`.readString()
        favoriteName = `in`.readString()
        isFavorited = `in`.readByte().toInt() != 0
        gid = `in`.readLong()
        torrentCount = `in`.readInt()
        language = `in`.readString()
        size = `in`.readString()
        SpiderInfoPages = `in`.readInt()
        favoriteCount = `in`.readInt()
        ratingCount = `in`.readInt()
        val tagList = ArrayList<TagGroup>()
        `in`.readTypedList(tagList, TagGroup.CREATOR)
        tags = if (tagList.isEmpty()) null else tagList
        previewPages = `in`.readInt()
        SpiderInfoPreviewPages = `in`.readInt()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        dest.writeString(thumb)
        dest.writeFloat(rating)
        dest.writeString(simpleLanguage)
        dest.writeLong(serverProfileId)
        dest.writeInt(pages)
        dest.writeInt(progress)
        dest.writeByte(if (rated) 1.toByte() else 0.toByte())
        dest.writeString(uploader)
        dest.writeString(favoriteName)
        dest.writeByte(if (isFavorited) 1.toByte() else 0.toByte())
        dest.writeLong(gid)
        dest.writeInt(torrentCount)
        dest.writeString(language)
        dest.writeString(size)
        dest.writeInt(SpiderInfoPages)
        dest.writeInt(favoriteCount)
        dest.writeInt(ratingCount)
        dest.writeTypedList(tags ?: emptyList())
        dest.writeInt(previewPages)
        dest.writeInt(SpiderInfoPreviewPages)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GalleryDetail> = object : Parcelable.Creator<GalleryDetail> {
            override fun createFromParcel(source: Parcel): GalleryDetail = GalleryDetail(source)
            override fun newArray(size: Int): Array<GalleryDetail?> = arrayOfNulls(size)
        }
    }
}
