package com.lanraragi.reader.domain

import android.os.Parcel
import android.os.Parcelable

/**
 * Extended archive information for the detail view.
 * Composes [Archive] with structured tag groups and file metadata.
 *
 * Implements [Parcelable] so the detail page can save/restore it via
 * `Scene.onSaveInstanceState`. After the M1b state-collapse this is
 * the canonical detail-page truth source — the standalone GalleryDetail
 * UI cache class was retired.
 */
data class ArchiveDetail(
    val archive: Archive,
    val tagGroups: List<TagGroup>,
    val language: String?,
    val size: String?,
) : Parcelable {

    @Suppress("DEPRECATION")
    constructor(parcel: Parcel) : this(
        archive = requireNotNull(
            parcel.readParcelable(Archive::class.java.classLoader)
        ) { "ArchiveDetail Parcel missing required Archive" },
        tagGroups = ArrayList<TagGroup>().also { list ->
            parcel.readTypedList(list, TagGroup.CREATOR)
        },
        language = parcel.readString(),
        size = parcel.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archive, flags)
        dest.writeTypedList(tagGroups)
        dest.writeString(language)
        dest.writeString(size)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ArchiveDetail> = object : Parcelable.Creator<ArchiveDetail> {
            override fun createFromParcel(parcel: Parcel): ArchiveDetail = ArchiveDetail(parcel)
            override fun newArray(size: Int): Array<ArchiveDetail?> = arrayOfNulls(size)
        }
    }
}
