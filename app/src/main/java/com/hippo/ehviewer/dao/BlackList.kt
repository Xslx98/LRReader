package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "Black_List", indices = [Index("BADGAYNAME")])
class BlackList(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    @JvmField
    var id: Long? = null,

    @ColumnInfo(name = "BADGAYNAME")
    @JvmField
    var badgayname: String? = null,

    @ColumnInfo(name = "REASON")
    @JvmField
    var reason: String? = null,

    @ColumnInfo(name = "ANGRYWITH")
    @JvmField
    var angrywith: String? = null,

    @ColumnInfo(name = "ADD_TIME")
    @JvmField
    var add_time: String? = null,

    @ColumnInfo(name = "MODE")
    @JvmField
    var mode: Int? = null
) {
    @Ignore
    constructor(id: Long?) : this(id, null, null, null, null, null)

    override fun toString(): String = badgayname ?: ""
}
