package com.hippo.ehviewer.client.data.userTag

import android.os.Parcel
import android.os.Parcelable
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.util.TagTranslationUtil

class UserTag : Parcelable {

    @JvmField var userTagId: String? = null
    @JvmField var tagName: String? = null
    @JvmField var watched: Boolean = false
    @JvmField var hidden: Boolean = false
    @JvmField var color: String? = null
    @JvmField var tagWeight: Int = 0

    constructor()

    private constructor(parcel: Parcel) {
        userTagId = parcel.readString()
        tagName = parcel.readString()
        watched = parcel.readByte().toInt() != 0
        hidden = parcel.readByte().toInt() != 0
        color = parcel.readString()
        tagWeight = parcel.readInt()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(userTagId)
        dest.writeString(tagName)
        dest.writeByte(if (watched) 1 else 0)
        dest.writeByte(if (hidden) 1 else 0)
        dest.writeString(color)
    }

    fun getName(ehTags: EhTagDatabase): String? {
        // 汉化标签
        val judge = AppearanceSettings.getShowTagTranslations()
        if (judge) {
            val name = tagName
            // 重设标签名称,并跳过已翻译的标签
            if (name != null && name.split(":").size == 2) {
                return TagTranslationUtil.getTagCN(name.split(":").toTypedArray(), ehTags)
            }
        }
        return tagName
    }

    fun getId(): Long = userTagId!!.substring(8).toLong()

    fun deleteParam(): String =
        "usertag_action=mass" +
            "&tagname_new=" +
            "&tagcolor_new=" +
            "&tagweight_new=" + tagWeight +
            "&modify_usertags%5B%5D=" + getId() +
            "&usertag_target=0"

    companion object CREATOR : Parcelable.Creator<UserTag> {
        override fun createFromParcel(parcel: Parcel): UserTag = UserTag(parcel)
        override fun newArray(size: Int): Array<UserTag?> = arrayOfNulls(size)
    }
}
