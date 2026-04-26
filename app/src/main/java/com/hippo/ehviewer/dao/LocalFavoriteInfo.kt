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
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index

/**
 * Entity mapped to table "LOCAL_FAVORITES".
 *
 * W36-9: standalone class. Used to inherit GalleryInfoEntity to share
 * the GalleryInfo column set; every non-DAO consumer was migrated to
 * the Archive domain model in Phase 1 and the inheritance only kept
 * dead fields alive.
 *
 * Persistent column set unchanged from Room schema v21 — physical
 * column drops happen in W36-10.
 */
@Entity(tableName = "LOCAL_FAVORITES", primaryKeys = ["ARCID"], indices = [Index("TIME")])
class LocalFavoriteInfo() : Parcelable {

    // ── Persistent columns (mirror Room v21 schema 1:1) ──

    @JvmField
    @ColumnInfo(name = "ARCID")
    var arcid: String = ""

    @JvmField
    @ColumnInfo(name = "GID")
    var gid: Long = 0

    @JvmField
    @ColumnInfo(name = "TITLE")
    var title: String? = null

    @JvmField
    @ColumnInfo(name = "TITLE_JPN")
    var titleJpn: String? = null

    @JvmField
    @ColumnInfo(name = "THUMB")
    var thumb: String? = null

    @JvmField
    @ColumnInfo(name = "CATEGORY")
    var category: Int = 0

    @JvmField
    @ColumnInfo(name = "POSTED")
    var posted: String? = null

    @JvmField
    @ColumnInfo(name = "UPLOADER")
    var uploader: String? = null

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
    @ColumnInfo(name = "TIME")
    var time: Long = 0

    // ── @Ignore transient fields (kept per W36-7 audit) ──

    /**
     * Display tags; populated by mergeOldDB legacy path. No production
     * reader at present, but kept conservatively to mirror pre-flatten
     * behavior (any future Archive→LocalFavoriteInfo mapper would set
     * it). Drop together with all dead @Ignore fields when the Phase 4
     * format overhaul lands.
     */
    @JvmField
    @Ignore
    var simpleTags: Array<String>? = null

    // ── Parcelable ──

    @Ignore
    private constructor(`in`: Parcel) : this() {
        arcid = `in`.readString() ?: ""
        gid = `in`.readLong()
        title = `in`.readString()
        titleJpn = `in`.readString()
        thumb = `in`.readString()
        category = `in`.readInt()
        posted = `in`.readString()
        uploader = `in`.readString()
        rating = `in`.readFloat()
        simpleLanguage = `in`.readString()
        serverProfileId = `in`.readLong()
        time = `in`.readLong()
        simpleTags = `in`.createStringArray()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeLong(gid)
        dest.writeString(title)
        dest.writeString(titleJpn)
        dest.writeString(thumb)
        dest.writeInt(category)
        dest.writeString(posted)
        dest.writeString(uploader)
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
