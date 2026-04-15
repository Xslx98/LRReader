package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "DOWNLOAD_DIRNAME")
class DownloadDirname(
    @PrimaryKey
    @ColumnInfo(name = "ARCID")
    var arcid: String = "",

    @ColumnInfo(name = "DIRNAME")
    var dirname: String? = null
)
