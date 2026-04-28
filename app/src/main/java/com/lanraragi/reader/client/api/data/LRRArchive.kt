package com.lanraragi.reader.client.api.data

import android.os.Parcel
import android.os.Parcelable
import com.hippo.ehviewer.client.data.GalleryDetail
import com.lanraragi.reader.client.api.LRRAuthManager

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.domain.parseLrrTagString
import com.lanraragi.reader.domain.parseRatingFromTags
import com.lanraragi.reader.domain.stripNamespace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * Represents a LANraragi archive. Maps to the JSON objects returned by
 * GET /api/search and GET /api/archives/:id/metadata.
 */
@Serializable
class LRRArchive() : Parcelable {

    @JvmField @SerialName("arcid") var arcid: String = ""
    @JvmField @SerialName("title") var title: String = ""
    @JvmField @SerialName("tags") var tags: String = ""
    @JvmField @SerialName("isnew") @Serializable(with = FlexibleStringSerializer::class) var isnew: String = "false"
    @JvmField @SerialName("extension") var extension: String = ""
    @JvmField @SerialName("filename") var filename: String = ""
    @JvmField @SerialName("pagecount") var pagecount: Int = 0
    @JvmField @SerialName("progress") var progress: Int = 0
    @JvmField @SerialName("lastreadtime") var lastreadtime: Long = 0
    @JvmField @SerialName("summary") var summary: String? = null

    /**
     * Convert this LRRArchive into a GalleryDetail for the detail scene.
     *
     * Post-W36-11 the GalleryDetail field set is intentionally narrower
     * than the historical GalleryInfoEntity it used to inherit; fields
     * with no UI reader (titleJpn / category / posted / simpleTags /
     * tgList) are no longer populated here.
     */
    fun toGalleryDetail(): GalleryDetail {
        val gd = GalleryDetail()

        gd.arcid = arcid
        gd.title = title
        gd.pages = pagecount
        gd.progress = progress

        val serverUrl = LRRAuthManager.getServerUrl()
        gd.thumb = if (serverUrl != null) getThumbnailUrl(serverUrl) else ""

        val parsedRatingDetail = parseRatingFromTags(tags)
        gd.rating = parsedRatingDetail
        gd.rated = parsedRatingDetail > 0
        gd.uploader = null

        gd.language = "N/A"
        gd.size = extension.uppercase().ifEmpty { "N/A" }

        val parsedTags = getParsedTags()
        if (parsedTags.isNotEmpty()) {
            gd.tags = parsedTags.map { (namespace, values) -> TagGroup(namespace, values) }
        }

        gd.serverProfileId = LRRAuthManager.getActiveProfileId()

        return gd
    }

    // ----- Domain model conversion -----

    /**
     * Convert this LRRArchive into an [Archive] domain model.
     * No hashing, no legacy field mapping — fields map 1:1.
     */
    fun toArchive(): Archive {
        val serverUrl = LRRAuthManager.getServerUrl()
        return Archive(
            arcid = arcid,
            title = title,
            tags = getParsedTags(),
            pagecount = pagecount,
            progress = progress,
            extension = extension,
            filename = filename,
            thumbnailUrl = if (serverUrl != null) getThumbnailUrl(serverUrl) else "",
            rating = parseRatingFromTags(tags),
            isnew = isNew(),
            lastreadtime = lastreadtime,
            summary = summary,
            serverProfileId = LRRAuthManager.getActiveProfileId(),
        )
    }

    /**
     * Convert this LRRArchive into an [ArchiveDetail] for the detail view.
     */
    fun toArchiveDetail(): ArchiveDetail {
        val parsedTags = getParsedTags()
        val tagGroups = parsedTags.map { (namespace, values) ->
            TagGroup(namespace, values)
        }
        return ArchiveDetail(
            archive = toArchive(),
            tagGroups = tagGroups,
            language = "N/A",
            size = extension.uppercase().ifEmpty { "N/A" },
        )
    }

    // ----- Helper methods -----

    /** @return true if this archive has the "new" flag set. */
    fun isNew(): Boolean = "true".equals(isnew, ignoreCase = true)

    /** Constructs the thumbnail URL for this archive. */
    fun getThumbnailUrl(baseUrl: String): String =
        com.lanraragi.reader.client.api.parseBaseUrl(baseUrl).newBuilder()
            .addPathSegments("api/archives")
            .addPathSegment(arcid)
            .addPathSegment("thumbnail")
            .build()
            .toString()

    /**
     * Parses the comma-separated tag string into a map of namespace → tag list.
     * Tags without a namespace are placed under "misc".
     */
    fun getParsedTags(): Map<String, List<String>> = parseLrrTagString(tags)

    /** @return Simple flat list of tag strings (without namespaces) for display. */
    fun getSimpleTags(): Array<String>? {
        if (tags.isEmpty()) return null
        return tags.split(",").map { stripNamespace(it) }.toTypedArray()
    }

    // ----- Parcelable -----

    private constructor(parcel: Parcel) : this() {
        arcid = parcel.readString() ?: ""
        title = parcel.readString() ?: ""
        tags = parcel.readString() ?: ""
        isnew = parcel.readString() ?: "false"
        extension = parcel.readString() ?: ""
        filename = parcel.readString() ?: ""
        pagecount = parcel.readInt()
        progress = parcel.readInt()
        lastreadtime = parcel.readLong()
        summary = parcel.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        dest.writeString(tags)
        dest.writeString(isnew)
        dest.writeString(extension)
        dest.writeString(filename)
        dest.writeInt(pagecount)
        dest.writeInt(progress)
        dest.writeLong(lastreadtime)
        dest.writeString(summary)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LRRArchive> = object : Parcelable.Creator<LRRArchive> {
            override fun createFromParcel(parcel: Parcel): LRRArchive = LRRArchive(parcel)
            override fun newArray(size: Int): Array<LRRArchive?> = arrayOfNulls(size)
        }
    }
}
