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
import com.hippo.ehviewer.download.DownloadState
import com.lanraragi.reader.domain.Archive

/**
 * In-memory view over a `download` row from `ARCHIVE_LOCAL_STATE`.
 *
 * Post-L1-4 this class is no longer a Room `@Entity` — the persisted
 * state lives on [ArchiveLocalState] (one row per arcid, with a
 * download subsystem when DOWNLOAD_STATE is non-null). UI / Adapter /
 * sync code keeps reading and writing `DownloadInfo` instances; the
 * repository layer translates between this view and the unified row.
 *
 * Mutable fields (state, legacy, label, archiveUri, time, etc.) are
 * preserved because the W36-era listeners and adapters mutate this
 * object in place. Demoting to an immutable data class would force a
 * larger UI refactor; that work belongs to a follow-up.
 *
 * Parcelable is kept because [DownloadInfo] still flows through Intent
 * extras in a few places (DownloadService, GalleryActivity).
 */
class DownloadInfo() : Parcelable {

    // ── Display fields (from Archive payload) ──

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

    // ── Download subsystem fields ──

    @JvmField
    var state: DownloadState = DownloadState.NONE

    @JvmField
    var legacy: Int = 0

    @JvmField
    var time: Long = 0

    @JvmField
    var label: String? = null

    @JvmField
    var archiveUri: String? = null

    /**
     * SAF tree URI of the download root in effect when this archive was
     * downloaded. Mirrored from `ARCHIVE_LOCAL_STATE.DOWNLOAD_ROOT_URI`.
     * NULL means "use the current `DownloadSettings.getDownloadLocation()`"
     * — applies to legacy rows before the v25→v26 boot backfill runs,
     * and to future rows where the user cleared the download location
     * entirely. Read paths (gallery viewer, resume worker, delete) prefer
     * this value over the current setting so old downloads stay
     * reachable after the user changes the setting.
     */
    @JvmField
    var downloadRootUri: String? = null

    // ── Transient, non-persisted helpers ──

    /**
     * Display tags; populated by the repository layer from the
     * decoded Archive's flat tag list.
     */
    @JvmField
    var simpleTags: Array<String>? = null

    /**
     * Search-side tag list, read by
     * [com.hippo.ehviewer.sync.DownloadListInfosExecutor.matchTag].
     */
    @JvmField
    var tgList: ArrayList<String>? = null

    /**
     * Cached size of the on-disk download directory; filled lazily by
     * [com.hippo.ehviewer.sync.DownloadListInfosExecutor] when sorting
     * by size. Not progress data — separate from
     * `DownloadProgressTracker` (see ADR-001).
     */
    @JvmField
    var fileSize: Long = -1

    // ── Parcelable ──

    private constructor(`in`: Parcel) : this() {
        arcid = `in`.readString() ?: ""
        title = `in`.readString()
        thumb = `in`.readString()
        rating = `in`.readFloat()
        simpleLanguage = `in`.readString()
        serverProfileId = `in`.readLong()
        state = DownloadState.fromCode(`in`.readInt())
        legacy = `in`.readInt()
        time = `in`.readLong()
        label = `in`.readString()
        archiveUri = `in`.readString()
        downloadRootUri = `in`.readString()
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
        dest.writeInt(state.code)
        dest.writeInt(legacy)
        dest.writeLong(time)
        dest.writeString(label)
        dest.writeString(archiveUri)
        dest.writeString(downloadRootUri)
        dest.writeStringArray(simpleTags)
        dest.writeList(tgList)
        dest.writeLong(fileSize)
    }

    /**
     * Refresh display fields from a re-fetched [Archive]. Called from
     * GalleryDetailScene after detail load to keep the cached download
     * row in sync with the latest server state. Note that this only
     * mutates the in-memory view; persistence happens via
     * [com.hippo.ehviewer.dao.DownloadDbRepository.putDownloadInfo].
     *
     * simpleLanguage is intentionally not refreshed because LRR never
     * populates it; the column has no producer in the post-L1 schema.
     */
    fun updateInfo(archive: Archive) {
        arcid = archive.arcid
        title = archive.title
        thumb = archive.thumbnailUrl
        rating = archive.rating
        // simpleTags is recomputed by the adapter from the live Archive
        // on the next bind, so no copy is needed here.
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<DownloadInfo> = object : Parcelable.Creator<DownloadInfo> {
            override fun createFromParcel(source: Parcel): DownloadInfo = DownloadInfo(source)
            override fun newArray(size: Int): Array<DownloadInfo?> = arrayOfNulls(size)
        }
    }
}
