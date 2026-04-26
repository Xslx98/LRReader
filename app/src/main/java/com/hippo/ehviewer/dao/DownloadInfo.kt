package com.hippo.ehviewer.dao

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfoEntity

/**
 * Entity mapped to table "DOWNLOADS".
 * Primary key is GID (inherited from GalleryInfo).
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
class DownloadInfo : GalleryInfoEntity {

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

    /**
     * Cached size of the on-disk download directory; filled lazily by
     * [com.hippo.ehviewer.sync.DownloadListInfosExecutor] when sorting by
     * size. Not progress data — separate from `DownloadProgressTracker`.
     */
    @JvmField
    @Ignore
    var fileSize: Long = -1

    constructor()

    @Ignore
    constructor(gid: Long) {
        this.gid = gid
    }

    @Ignore
    constructor(
        gid: Long, arcid: String?, title: String?, titleJpn: String?, thumb: String?,
        category: Int, posted: String?, uploader: String?, rating: Float,
        simpleLanguage: String?, state: Int, legacy: Int, time: Long,
        label: String?, archiveUri: String?
    ) {
        this.gid = gid
        this.arcid = arcid ?: ""
        this.title = title
        this.titleJpn = titleJpn
        this.thumb = thumb
        this.category = category
        this.posted = posted
        this.uploader = uploader
        this.rating = rating
        this.simpleLanguage = simpleLanguage
        this.state = state
        this.legacy = legacy
        this.time = time
        this.label = label
        this.archiveUri = archiveUri
    }

    @Ignore
    constructor(galleryInfo: GalleryInfo) {
        gid = galleryInfo.gid
        arcid = galleryInfo.arcid
        title = galleryInfo.title
        titleJpn = galleryInfo.titleJpn
        thumb = galleryInfo.thumb
        category = galleryInfo.category
        posted = galleryInfo.posted
        uploader = galleryInfo.uploader
        rating = galleryInfo.rating
        simpleTags = galleryInfo.simpleTags
        simpleLanguage = galleryInfo.simpleLanguage
        serverProfileId = galleryInfo.serverProfileId
    }

    @Ignore
    protected constructor(`in`: Parcel) : super(`in`) {
        state = `in`.readInt()
        legacy = `in`.readInt()
        time = `in`.readLong()
        label = `in`.readString()
        archiveUri = `in`.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeInt(state)
        dest.writeInt(legacy)
        dest.writeLong(time)
        dest.writeString(label)
        dest.writeString(archiveUri)
    }

    fun updateInfo(galleryInfo: GalleryInfo) {
        arcid = galleryInfo.arcid
        title = galleryInfo.title
        titleJpn = galleryInfo.titleJpn
        thumb = galleryInfo.thumb
        category = galleryInfo.category
        posted = galleryInfo.posted
        uploader = galleryInfo.uploader
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
