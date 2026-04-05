package com.hippo.ehviewer.dao

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.hippo.ehviewer.client.data.GalleryInfo

/**
 * Entity mapped to table "HISTORY".
 * Primary key is GID (inherited from GalleryInfo).
 */
@Entity(
    tableName = "HISTORY",
    primaryKeys = ["GID"],
    indices = [Index("SERVER_PROFILE_ID"), Index("TIME")]
)
class HistoryInfo : GalleryInfo {

    @ColumnInfo(name = "MODE")
    @JvmField
    var mode: Int = 0

    @ColumnInfo(name = "TIME")
    @JvmField
    var time: Long = 0

    constructor()

    @Ignore
    constructor(gid: Long) {
        this.gid = gid
    }

    @Ignore
    constructor(
        gid: Long, token: String?, title: String?, titleJpn: String?,
        thumb: String?, category: Int, posted: String?, uploader: String?,
        rating: Float, simpleLanguage: String?, mode: Int, time: Long
    ) {
        this.gid = gid
        this.token = token
        this.title = title
        this.titleJpn = titleJpn
        this.thumb = thumb
        this.category = category
        this.posted = posted
        this.uploader = uploader
        this.rating = rating
        this.simpleLanguage = simpleLanguage
        this.mode = mode
        this.time = time
    }

    @Ignore
    constructor(galleryInfo: GalleryInfo) {
        gid = galleryInfo.gid
        token = galleryInfo.token
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
        mode = `in`.readInt()
        time = `in`.readLong()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeInt(mode)
        dest.writeLong(time)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HistoryInfo> = object : Parcelable.Creator<HistoryInfo> {
            override fun createFromParcel(source: Parcel): HistoryInfo = HistoryInfo(source)
            override fun newArray(size: Int): Array<HistoryInfo?> = arrayOfNulls(size)
        }
    }
}
