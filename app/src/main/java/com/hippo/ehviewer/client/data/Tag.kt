package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import java.nio.charset.StandardCharsets

class Tag : Parcelable {
    @JvmField var english: String? = null
    @JvmField var chinese: String? = null

    constructor(content: String) {
        val cArray = content.split("\r")
        chinese = String(Base64.decode(cArray[1], Base64.DEFAULT), StandardCharsets.UTF_8)
        english = content
    }

    constructor(english: String?, chinese: String?) {
        this.chinese = chinese
        this.english = english
    }

    private constructor(parcel: Parcel)

    fun involve(chars: String): Boolean {
        if (english != null && english!!.contains(chars)) {
            return true
        }
        return chinese != null && chinese!!.contains(chars)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
    }

    companion object CREATOR : Parcelable.Creator<Tag> {
        override fun createFromParcel(parcel: Parcel): Tag = Tag(parcel)
        override fun newArray(size: Int): Array<Tag?> = arrayOfNulls(size)
    }
}
