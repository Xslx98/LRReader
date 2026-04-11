package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable

/**
 * UI-layer representation of a gallery entry.
 *
 * This class holds all fields needed to display a gallery in lists, detail
 * pages, and the reader. Unlike [GalleryInfoEntity] (which carries Room
 * annotations for persistence), this class is a plain Parcelable data holder
 * with no ORM coupling.
 *
 * Convert between layers using the mapper functions in
 * `com.hippo.ehviewer.mapper`.
 */
class GalleryInfoUi : Parcelable {

    @JvmField var gid: Long = 0
    @JvmField var token: String? = null
    @JvmField var title: String? = null
    @JvmField var titleJpn: String? = null
    @JvmField var thumb: String? = null
    @JvmField var category: Int = 0
    @JvmField var posted: String? = null
    @JvmField var uploader: String? = null
    @JvmField var rating: Float = 0f
    @JvmField var rated: Boolean = false
    @JvmField var simpleTags: Array<String>? = null
    @JvmField var pages: Int = 0
    @JvmField var progress: Int = 0
    @JvmField var thumbWidth: Int = 0
    @JvmField var thumbHeight: Int = 0
    @JvmField var spanSize: Int = 0
    @JvmField var spanIndex: Int = 0
    @JvmField var spanGroupIndex: Int = 0
    @JvmField var tgList: ArrayList<String>? = null
    @JvmField var simpleLanguage: String? = null
    @JvmField var favoriteSlot: Int = -2
    @JvmField var favoriteName: String? = null
    @JvmField var serverProfileId: Long = 0

    constructor()

    protected constructor(`in`: Parcel) {
        gid = `in`.readLong()
        token = `in`.readString()
        title = `in`.readString()
        titleJpn = `in`.readString()
        thumb = `in`.readString()
        category = `in`.readInt()
        posted = `in`.readString()
        uploader = `in`.readString()
        rating = `in`.readFloat()
        rated = `in`.readByte().toInt() != 0
        simpleLanguage = `in`.readString()
        simpleTags = `in`.createStringArray()
        thumbWidth = `in`.readInt()
        thumbHeight = `in`.readInt()
        spanSize = `in`.readInt()
        spanIndex = `in`.readInt()
        spanGroupIndex = `in`.readInt()
        favoriteSlot = `in`.readInt()
        favoriteName = `in`.readString()
        @Suppress("UNCHECKED_CAST")
        tgList = `in`.readArrayList(String::class.java.classLoader) as? ArrayList<String>
        serverProfileId = `in`.readLong()
        progress = `in`.readInt()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(gid)
        dest.writeString(token)
        dest.writeString(title)
        dest.writeString(titleJpn)
        dest.writeString(thumb)
        dest.writeInt(category)
        dest.writeString(posted)
        dest.writeString(uploader)
        dest.writeFloat(rating)
        dest.writeByte(if (rated) 1.toByte() else 0.toByte())
        dest.writeString(simpleLanguage)
        dest.writeStringArray(simpleTags)
        dest.writeInt(thumbWidth)
        dest.writeInt(thumbHeight)
        dest.writeInt(spanSize)
        dest.writeInt(spanIndex)
        dest.writeInt(spanGroupIndex)
        dest.writeInt(favoriteSlot)
        dest.writeString(favoriteName)
        dest.writeList(tgList)
        dest.writeLong(serverProfileId)
        dest.writeInt(progress)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GalleryInfoUi> = object : Parcelable.Creator<GalleryInfoUi> {
            override fun createFromParcel(source: Parcel): GalleryInfoUi = GalleryInfoUi(source)
            override fun newArray(size: Int): Array<GalleryInfoUi?> = arrayOfNulls(size)
        }
    }
}
