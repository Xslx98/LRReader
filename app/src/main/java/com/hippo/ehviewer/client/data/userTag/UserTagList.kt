package com.hippo.ehviewer.client.data.userTag

import android.os.Parcel
import android.os.Parcelable

class UserTagList : Parcelable {

    @JvmField var userTags: MutableList<UserTag>
    @JvmField var stageId: Int = 0

    constructor() {
        userTags = ArrayList()
    }

    private constructor(parcel: Parcel) {
        userTags = parcel.createTypedArrayList(UserTag.CREATOR) ?: ArrayList()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedList(userTags)
    }

    operator fun get(index: Int): UserTag = userTags[index]

    fun size(): Int = userTags.size

    companion object CREATOR : Parcelable.Creator<UserTagList> {
        override fun createFromParcel(parcel: Parcel): UserTagList = UserTagList(parcel)
        override fun newArray(size: Int): Array<UserTagList?> = arrayOfNulls(size)
    }
}
