package com.hippo.ehviewer.dao

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.hippo.ehviewer.client.data.GalleryInfo
import org.json.JSONException
import org.json.JSONObject

/**
 * Entity mapped to table "DOWNLOADS".
 * Primary key is GID (inherited from GalleryInfo).
 */
@Entity(
    tableName = "DOWNLOADS",
    primaryKeys = ["GID"],
    indices = [
        Index("SERVER_PROFILE_ID"),
        Index("TIME")
    ]
)
class DownloadInfo : GalleryInfo {

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

    @JvmField
    @Ignore
    var speed: Long = 0

    @JvmField
    @Ignore
    var remaining: Long = 0

    @JvmField
    @Ignore
    var finished: Int = 0

    @JvmField
    @Ignore
    var downloaded: Int = 0

    @JvmField
    @Ignore
    var total: Int = 0

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
        gid: Long, token: String?, title: String?, titleJpn: String?, thumb: String?,
        category: Int, posted: String?, uploader: String?, rating: Float,
        simpleLanguage: String?, state: Int, legacy: Int, time: Long,
        label: String?, archiveUri: String?
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
        this.state = state
        this.legacy = legacy
        this.time = time
        this.label = label
        this.archiveUri = archiveUri
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

    override fun toJson(): JSONObject {
        val jsonObject = super.toJson()
        try {
            jsonObject.put("finished", finished)
            jsonObject.put("legacy", legacy)
            jsonObject.put("label", label)
            jsonObject.put("downloaded", downloaded)
            jsonObject.put("remaining", remaining)
            jsonObject.put("speed", speed)
            jsonObject.put("state", state)
            jsonObject.put("time", time)
            jsonObject.put("total", total)
            jsonObject.put("archiveUri", archiveUri)
        } catch (_: JSONException) {
        }
        return jsonObject
    }

    companion object {
        const val STATE_INVALID: Int = -1
        const val STATE_NONE: Int = 0
        const val STATE_WAIT: Int = 1
        const val STATE_DOWNLOAD: Int = 2
        const val STATE_FINISH: Int = 3
        const val STATE_FAILED: Int = 4
        const val STATE_UPDATE: Int = 5
        const val GOTO_NEW: Int = 6

        @JvmField
        val CREATOR: Parcelable.Creator<DownloadInfo> = object : Parcelable.Creator<DownloadInfo> {
            override fun createFromParcel(source: Parcel): DownloadInfo = DownloadInfo(source)
            override fun newArray(size: Int): Array<DownloadInfo?> = arrayOfNulls(size)
        }

        @JvmStatic
        @Throws(ClassCastException::class)
        fun downloadInfoFromJson(`object`: JSONObject): DownloadInfo {
            val downloadInfo = galleryInfoFromJson(`object`) as DownloadInfo
            downloadInfo.finished = `object`.optInt("finished", 0)
            downloadInfo.legacy = `object`.optInt("legacy", 0)
            downloadInfo.label = `object`.optString("label", null)
            downloadInfo.downloaded = `object`.optInt("downloaded", 0)
            downloadInfo.remaining = `object`.optLong("remaining", 0)
            downloadInfo.speed = `object`.optLong("speed", 0)
            downloadInfo.state = `object`.optInt("state", 0)
            downloadInfo.time = `object`.optLong("time", 0)
            downloadInfo.total = `object`.optInt("total", 0)
            downloadInfo.archiveUri = `object`.optString("archiveUri", null)
            return downloadInfo
        }
    }
}
