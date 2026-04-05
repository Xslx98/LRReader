package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable

class NewVersion : Parcelable {
    @JvmField var versionUrl: String? = null
    @JvmField var versionName: String? = null

    constructor()

    private constructor(parcel: Parcel) {
        versionName = parcel.readString()
        versionUrl = parcel.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(versionName)
        dest.writeString(versionUrl)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<NewVersion> {
        override fun createFromParcel(parcel: Parcel): NewVersion = NewVersion(parcel)
        override fun newArray(size: Int): Array<NewVersion?> = arrayOfNulls(size)
    }
}
