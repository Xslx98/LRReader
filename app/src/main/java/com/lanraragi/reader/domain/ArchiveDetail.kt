package com.lanraragi.reader.domain

import android.os.Parcel
import android.os.Parcelable

/**
 * Extended archive information for the detail view.
 * Composes [Archive] with structured tag groups and file metadata.
 *
 * Implements [Parcelable] so the detail page can save/restore it via
 * `Scene.onSaveInstanceState`. M1b-4 will switch the savedInstanceState
 * key from the legacy `KEY_GALLERY_DETAIL` to a Parcelable
 * `KEY_ARCHIVE_DETAIL`, eliminating the need for the standalone
 * `GalleryDetail` UI cache class.
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
