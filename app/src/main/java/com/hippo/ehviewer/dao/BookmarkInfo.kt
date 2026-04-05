package com.hippo.ehviewer.dao

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import com.hippo.ehviewer.client.data.GalleryInfo

/**
 * Entity mapped to table "BOOKMARKS".
 * Primary key is GID (inherited from GalleryInfo).
 */
@Entity(tableName = "BOOKMARKS", primaryKeys = ["GID"])
class BookmarkInfo : GalleryInfo {

    @ColumnInfo(name = "PAGE")
    @JvmField
    var page: Int = 0

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
        rating: Float, simpleLanguage: String?, page: Int, time: Long
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
        this.page = page
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
    }

    @Ignore
    protected constructor(`in`: Parcel) : super(`in`) {
        page = `in`.readInt()
        time = `in`.readLong()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeInt(page)
        dest.writeLong(time)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BookmarkInfo> = object : Parcelable.Creator<BookmarkInfo> {
            override fun createFromParcel(source: Parcel): BookmarkInfo = BookmarkInfo(source)
            override fun newArray(size: Int): Array<BookmarkInfo?> = arrayOfNulls(size)
        }
    }
}
