package com.hippo.ehviewer.gallery

import android.os.Parcel
import android.os.Parcelable

/**
 * Everything a tank composite reader session needs to boot, handed through
 * the launch intent ([com.hippo.ehviewer.ui.GalleryActivity.ACTION_TANK]).
 *
 * The member snapshot is deliberately MINIMAL (arcid / title / metadata
 * pagecount) to stay well inside the binder transaction budget for large
 * tanks — the provider fetches everything else (file lists, bytes) itself.
 * Member order is the server's tank order at capture time.
 */
class TankSessionSeed(
    val tankId: String,
    val tankName: String,
    val profileId: Long,
    val members: List<TankMemberSeed>,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(tankId)
        dest.writeString(tankName)
        dest.writeLong(profileId)
        dest.writeTypedList(members)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TankSessionSeed> =
            object : Parcelable.Creator<TankSessionSeed> {
                override fun createFromParcel(parcel: Parcel): TankSessionSeed {
                    val tankId = parcel.readString().orEmpty()
                    val tankName = parcel.readString().orEmpty()
                    val profileId = parcel.readLong()
                    val members = ArrayList<TankMemberSeed>()
                    parcel.readTypedList(members, TankMemberSeed.CREATOR)
                    return TankSessionSeed(tankId, tankName, profileId, members)
                }

                override fun newArray(size: Int): Array<TankSessionSeed?> = arrayOfNulls(size)
            }
    }
}

/** One member in a [TankSessionSeed]: identity + display + metadata pagecount. */
class TankMemberSeed(
    val arcid: String,
    val title: String,
    /** Metadata pagecount (may disagree with the real file list; see [TankPageMap.correct]). */
    val pagecount: Int,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(arcid)
        dest.writeString(title)
        dest.writeInt(pagecount)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TankMemberSeed> =
            object : Parcelable.Creator<TankMemberSeed> {
                override fun createFromParcel(parcel: Parcel): TankMemberSeed = TankMemberSeed(
                    arcid = parcel.readString().orEmpty(),
                    title = parcel.readString().orEmpty(),
                    pagecount = parcel.readInt(),
                )

                override fun newArray(size: Int): Array<TankMemberSeed?> = arrayOfNulls(size)
            }
    }
}
