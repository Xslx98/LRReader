package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "FILTER")
class Filter(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long? = null,

    @ColumnInfo(name = "MODE")
    @JvmField
    var mode: Int = 0,

    @ColumnInfo(name = "TEXT")
    @JvmField
    var text: String? = null,

    @ColumnInfo(name = "ENABLE")
    @JvmField
    var enable: Boolean? = null
) {
    @Ignore
    constructor(id: Long?) : this(id, 0, null, null)

    override fun hashCode(): Int = 31 * mode + (text?.hashCode() ?: 0)

    override fun equals(other: Any?): Boolean {
        if (other !is Filter) return false
        return other.mode == mode && other.text == text
    }
}
