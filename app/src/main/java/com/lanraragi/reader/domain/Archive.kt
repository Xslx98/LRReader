package com.lanraragi.reader.domain

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.Serializable

/**
 * Shared domain model representing a LANraragi archive.
 * Immutable data class — the canonical representation used across
 * UI and business logic layers.
 *
 * Uses the natural LANraragi identifier [arcid] (String) rather than
 * the legacy hashed gid (Long).
 *
 * Implements [Parcelable] for Android IPC (ContentHelper, Intent extras)
 * and `@Serializable` so the entire archive can be JSON-encoded into a
 * single column when the W2026-04 audit's L1 (three-table unification)
 * lands. The JSON form is forward-compatible: adding new fields here
 * does not require a Room schema bump.
 */
@Serializable
data class Archive(
    val arcid: String,
    val title: String,
    val tags: Map<String, List<String>>,
    val pagecount: Int,
    val progress: Int,
    val extension: String,
    val filename: String,
    val thumbnailUrl: String,
    val rating: Float,
    val isnew: Boolean,
    val lastreadtime: Long,
    val summary: String? = null,
    val serverProfileId: Long,
) : Parcelable {

    /** Flat list of all tag values across namespaces, for display/search. */
    val flatTags: List<String>
        get() = tags.values.flatten()

    constructor(parcel: Parcel) : this(
        arcid = parcel.readString() ?: "",
        title = parcel.readString() ?: "",
        tags = readTagsMap(parcel),
        pagecount = parcel.readInt(),
        progress = parcel.readInt(),
        extension = parcel.readString() ?: "",
        filename = parcel.readString() ?: "",
        thumbnailUrl = parcel.readString() ?: "",
        rating = parcel.readFloat(),
        isnew = parcel.readByte() != 0.toByte(),
        lastreadtime = parcel.readLong(),
        summary = parcel.readString(),
        serverProfileId = parcel.readLong(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        writeTagsMap(dest, tags)
        dest.writeInt(pagecount)
        dest.writeInt(progress)
        dest.writeString(extension)
        dest.writeString(filename)
        dest.writeString(thumbnailUrl)
        dest.writeFloat(rating)
        dest.writeByte(if (isnew) 1 else 0)
        dest.writeLong(lastreadtime)
        dest.writeString(summary)
        dest.writeLong(serverProfileId)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Archive> = object : Parcelable.Creator<Archive> {
            override fun createFromParcel(parcel: Parcel): Archive = Archive(parcel)
            override fun newArray(size: Int): Array<Archive?> = arrayOfNulls(size)
        }

        private fun writeTagsMap(dest: Parcel, tags: Map<String, List<String>>) {
            dest.writeInt(tags.size)
            for ((key, values) in tags) {
                dest.writeString(key)
                dest.writeStringList(values)
            }
        }

        private fun readTagsMap(parcel: Parcel): Map<String, List<String>> {
            val size = parcel.readInt()
            if (size == 0) return emptyMap()
            val map = LinkedHashMap<String, List<String>>(size)
            repeat(size) {
                val key = parcel.readString() ?: ""
                val values = mutableListOf<String>()
                parcel.readStringList(values)
                map[key] = values
            }
            return map
        }
    }
}
