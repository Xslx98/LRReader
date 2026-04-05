package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "DOWNLOAD_LABELS")
class DownloadLabel(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long? = null,

    @ColumnInfo(name = "LABEL")
    var label: String? = null,

    @ColumnInfo(name = "TIME")
    var time: Long = 0
) {
    @Ignore
    constructor(id: Long?) : this(id, null, 0)
}
