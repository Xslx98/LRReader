package com.lanraragi.reader.domain

import android.os.Parcel
import android.os.Parcelable

/**
 * A group of tags under a single namespace (e.g., "artist", "parody", "misc").
 *
 * Implements [Parcelable] so it can ride inside other Parcelable models
 * (notably [ArchiveDetail]) without requiring a separate adapter type.
 */
data class TagGroup(
    val namespace: String,
    val tags: List<String>,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        namespace = parcel.readString() ?: "",
        tags = parcel.createStringArrayList() ?: emptyList(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(namespace)
        dest.writeStringList(tags)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TagGroup> = object : Parcelable.Creator<TagGroup> {
            override fun createFromParcel(parcel: Parcel): TagGroup = TagGroup(parcel)
            override fun newArray(size: Int): Array<TagGroup?> = arrayOfNulls(size)
        }
    }
}
