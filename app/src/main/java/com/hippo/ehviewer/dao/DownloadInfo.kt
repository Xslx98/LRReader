/*
 * Copyright 2018 Hippo Seven
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
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.hippo.ehviewer.client.data.GalleryInfo

/**
 * Entity mapped to table "DOWNLOADS".
 *
 * W36-7: this is now a standalone class. It used to inherit from
 * GalleryInfoEntity to share the GalleryInfo column set, but every
 * non-DAO consumer was migrated to the Archive domain model in Phase 1
 * (commits W36-1..6) and the inheritance only kept dead fields alive.
 *
 * Persistent column set is unchanged from Room schema v21 — physical
 * column drops (GID / TITLE_JPN / CATEGORY / POSTED / UPLOADER) happen
 * in a single bump in W36-10.
 */
@Entity(
    tableName = "DOWNLOADS",
    primaryKeys = ["ARCID"],
    indices = [
        Index("SERVER_PROFILE_ID"),
        Index("TIME"),
        Index("LABEL")
    ]
)
class DownloadInfo() : Parcelable {

    // ── Persistent columns (mirror Room v21 schema 1:1) ──

    @JvmField
    @ColumnInfo(name = "ARCID")
    var arcid: String = ""

    @JvmField
    @ColumnInfo(name = "TITLE")
    var title: String? = null

    @JvmField
    @ColumnInfo(name = "THUMB")
    var thumb: String? = null

    @JvmField
    @ColumnInfo(name = "RATING")
    var rating: Float = 0f

    @JvmField
    @ColumnInfo(name = "SIMPLE_LANGUAGE")
    var simpleLanguage: String? = null

    @JvmField
    @ColumnInfo(name = "SERVER_PROFILE_ID", defaultValue = "0")
    var serverProfileId: Long = 0

    @JvmField
    @ColumnInfo(name = "STATE")
    var state: Int = 0

    @JvmField
    @ColumnInfo(name = "LEGACY")
    var legacy: Int = 0

    @JvmField
    @ColumnInfo(name = "TIME")
    var time: Long = 0

    @JvmField
    @ColumnInfo(name = "LABEL")
    var label: String? = null

    @JvmField
    @ColumnInfo(name = "ARCHIVE_URI")
    var archiveUri: String? = null

    // ── @Ignore transient fields (kept per Step 1 audit) ──

    /**
     * Display tags; populated by [com.hippo.ehviewer.mapper.toDownloadInfo]
     * from Archive.flatTags.
     */
    @JvmField
    @Ignore
    var simpleTags: Array<String>? = null

    /**
     * Search-side tag list, read by
     * [com.hippo.ehviewer.sync.DownloadListInfosExecutor.matchTag].
     */
    @JvmField
    @Ignore
    var tgList: ArrayList<String>? = null

    /**
     * Cached size of the on-disk download directory; filled lazily by
     * [com.hippo.ehviewer.sync.DownloadListInfosExecutor] when sorting by
     * size. Not progress data — separate from `DownloadProgressTracker`
     * (see ADR-001).
     */
    @JvmField
    @Ignore
    var fileSize: Long = -1

    // ── Parcelable ──

    @Ignore
    private constructor(`in`: Parcel) : this() {
        arcid = `in`.readString() ?: ""
        title = `in`.readString()
        thumb = `in`.readString()
        rating = `in`.readFloat()
        simpleLanguage = `in`.readString()
        serverProfileId = `in`.readLong()
        state = `in`.readInt()
        legacy = `in`.readInt()
        time = `in`.readLong()
        label = `in`.readString()
        archiveUri = `in`.readString()
        simpleTags = `in`.createStringArray()
        @Suppress("UNCHECKED_CAST")
        tgList = `in`.readArrayList(String::class.java.classLoader) as? ArrayList<String>
        fileSize = `in`.readLong()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        dest.writeString(thumb)
        dest.writeFloat(rating)
        dest.writeString(simpleLanguage)
        dest.writeLong(serverProfileId)
        dest.writeInt(state)
        dest.writeInt(legacy)
        dest.writeLong(time)
        dest.writeString(label)
        dest.writeString(archiveUri)
        dest.writeStringArray(simpleTags)
        dest.writeList(tgList)
        dest.writeLong(fileSize)
    }

    /**
     * Refresh display fields from a re-fetched [GalleryInfo] (typically
     * a [com.hippo.ehviewer.client.data.GalleryDetail] returned by the
     * server). Called from GalleryDetailScene after detail load to keep
     * the cached download row in sync with the latest server state.
     */
    fun updateInfo(galleryInfo: GalleryInfo) {
        arcid = galleryInfo.arcid
        title = galleryInfo.title
        thumb = galleryInfo.thumb
        rating = galleryInfo.rating
        simpleTags = galleryInfo.simpleTags
        simpleLanguage = galleryInfo.simpleLanguage
    }

    companion object {
        const val STATE_INVALID: Int = -1
        const val STATE_NONE: Int = 0
        const val STATE_WAIT: Int = 1
        const val STATE_DOWNLOAD: Int = 2
        const val STATE_FINISH: Int = 3
        const val STATE_FAILED: Int = 4

        @JvmField
        val CREATOR: Parcelable.Creator<DownloadInfo> = object : Parcelable.Creator<DownloadInfo> {
            override fun createFromParcel(source: Parcel): DownloadInfo = DownloadInfo(source)
            override fun newArray(size: Int): Array<DownloadInfo?> = arrayOfNulls(size)
        }
    }
}
