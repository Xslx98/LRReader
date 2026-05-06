package com.lanraragi.reader.client.api.data

import android.os.Parcel
import android.os.Parcelable
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

    // ----- Domain model conversion -----

    /**
     * Convert this LRRArchive into an [Archive] domain model.
     * No hashing, no legacy field mapping — fields map 1:1.
     *
     * @param sourceProfileId id of the profile this metadata was fetched
     *   against. Defaults to the active profile, which is correct for
     *   search results / paging / any "browse the active server" path.
     *   The detail view-model passes the archive's pinned source profile
     *   id explicitly so a cross-server detail fetch (e.g. while active
     *   is profile A but the archive belongs to profile B) does NOT
     *   corrupt the in-memory Archive's `serverProfileId` to the active
     *   value — that would make the badge flip and route subsequent
     *   refreshes to the wrong server.
     * @param sourceBaseUrl base URL of [sourceProfileId]'s server, used
     *   to construct [Archive.thumbnailUrl]. Defaults to the active
     *   server URL, again correct for the browse path. Cross-server
     *   callers pass the originating server's URL.
     */
    fun toArchive(
        sourceProfileId: Long = LRRAuthManager.getActiveProfileId(),
        sourceBaseUrl: String? = LRRAuthManager.getServerUrl(),
    ): Archive {
        return Archive(
            arcid = arcid,
            title = title,
            tags = getParsedTags(),
            pagecount = pagecount,
            progress = progress,
            extension = extension,
            filename = filename,
            thumbnailUrl = if (sourceBaseUrl != null) getThumbnailUrl(sourceBaseUrl) else "",
            rating = parseRatingFromTags(tags),
            isnew = isNew(),
            lastreadtime = lastreadtime,
            summary = summary,
            serverProfileId = sourceProfileId,
        )
    }

    /**
     * Convert this LRRArchive into an [ArchiveDetail] for the detail view.
     *
     * @param sourceProfileId @see [toArchive]
     * @param sourceBaseUrl @see [toArchive]
     */
    fun toArchiveDetail(
        sourceProfileId: Long = LRRAuthManager.getActiveProfileId(),
        sourceBaseUrl: String? = LRRAuthManager.getServerUrl(),
    ): ArchiveDetail {
        val parsedTags = getParsedTags()
        val tagGroups = parsedTags.map { (namespace, values) ->
            TagGroup(namespace, values)
        }
        return ArchiveDetail(
            archive = toArchive(sourceProfileId, sourceBaseUrl),
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
            .addPathSegment(com.lanraragi.reader.client.api.requireValidArcid(arcid))
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
