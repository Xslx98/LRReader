package com.lanraragi.reader.client.api.data

import android.os.Parcel
import android.os.Parcelable
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.lanraragi.reader.client.api.LRRAuthManager

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.TagGroup
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
            val tagGroups = mutableListOf<GalleryTagGroup>()
            for ((namespace, values) in parsedTags) {
                val group = GalleryTagGroup()
                group.groupName = namespace
                for (tag in values) {
                    group.addTag(tag)
                }
                tagGroups.add(group)
            }
            gd.tags = tagGroups.toTypedArray()
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
    fun getParsedTags(): Map<String, List<String>> {
        if (tags.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, MutableList<String>>()
        for (raw in tags.split(",")) {
            val tag = raw.trim()
            if (tag.isEmpty()) continue

            val colonIdx = tag.indexOf(':')
            val namespace: String
            val value: String
            if (colonIdx > 0) {
                namespace = tag.substring(0, colonIdx).trim()
                value = tag.substring(colonIdx + 1).trim()
            } else {
                namespace = "misc"
                value = tag
            }
            result.getOrPut(namespace) { mutableListOf() }.add(value)
        }
        return result
    }

    /** @return Simple flat list of tag strings (without namespaces) for display. */
    fun getSimpleTags(): Array<String>? {
        if (tags.isEmpty()) return null
        return tags.split(",").map { t ->
            val trimmed = t.trim()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) trimmed.substring(colonIdx + 1).trim() else trimmed
        }.toTypedArray()
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

        /**
         * Parse rating from the tags string. LANraragi uses emoji format: "rating:⭐⭐⭐⭐"
         * @return rating as float (1-5), or -1 if no rating tag found
         */
        @JvmStatic
        fun parseRatingFromTags(tags: String?): Float {
            if (tags.isNullOrEmpty()) return -1.0f
            for (part in tags.split(",")) {
                val trimmed = part.trim()
                if (trimmed.startsWith("rating:")) {
                    val ratingValue = trimmed.substring(7).trim()
                    var starCount = 0
                    var i = 0
                    while (i < ratingValue.length) {
                        val cp = ratingValue.codePointAt(i)
                        if (cp == 0x2B50) starCount++ // ⭐
                        i += Character.charCount(cp)
                    }
                    if (starCount > 0) {
                        return starCount.coerceAtMost(5).toFloat()
                    }
                    return try {
                        ratingValue.toFloat()
                    } catch (_: NumberFormatException) {
                        -1.0f
                    }
                }
            }
            return -1.0f
        }

        /**
         * Build a rating tag value using ⭐ emojis.
         * e.g. starCount=4 -> "⭐⭐⭐⭐"
         */
        @JvmStatic
        fun buildRatingEmoji(starCount: Int): String =
            "⭐".repeat(starCount.coerceAtMost(5))
    }
}
