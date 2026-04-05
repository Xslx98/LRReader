package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import org.json.JSONObject
import java.util.Date

/**
 * Entity mapped to table "Gallery_Tags".
 */
@Entity(tableName = "Gallery_Tags")
@TypeConverters(DateConverter::class)
class GalleryTags(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    @JvmField
    var gid: Long = 0,

    @ColumnInfo(name = "ROWS")
    @JvmField
    var rows: String? = null,

    @ColumnInfo(name = "ARTIST")
    @JvmField
    var artist: String? = null,

    @ColumnInfo(name = "COSPLAYER")
    @JvmField
    var cosplayer: String? = null,

    @ColumnInfo(name = "CHARACTER")
    @JvmField
    var character: String? = null,

    @ColumnInfo(name = "FEMALE")
    @JvmField
    var female: String? = null,

    @ColumnInfo(name = "GROUP")
    @JvmField
    var group: String? = null,

    @ColumnInfo(name = "LANGUAGE")
    @JvmField
    var language: String? = null,

    @ColumnInfo(name = "MALE")
    @JvmField
    var male: String? = null,

    @ColumnInfo(name = "MISC")
    @JvmField
    var misc: String? = null,

    @ColumnInfo(name = "MIXED")
    @JvmField
    var mixed: String? = null,

    @ColumnInfo(name = "OTHER")
    @JvmField
    var other: String? = null,

    @ColumnInfo(name = "PARODY")
    @JvmField
    var parody: String? = null,

    @ColumnInfo(name = "RECLASS")
    @JvmField
    var reclass: String? = null,

    @ColumnInfo(name = "CREATE_TIME")
    @JvmField
    var create_time: Date? = null,

    @ColumnInfo(name = "UPDATE_TIME")
    @JvmField
    var update_time: Date? = null
) {
    @Ignore
    constructor(gid: Long) : this(gid, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)

    override fun toString(): String {
        return try {
            JSONObject().apply {
                put("gid", gid)
                put("rows", rows)
                put("artist", artist)
                put("cosplayer", cosplayer)
                put("character", character)
                put("female", female)
                put("group", group)
                put("language", language)
                put("male", male)
                put("misc", misc)
                put("mixed", mixed)
                put("other", other)
                put("parody", parody)
                put("reclass", reclass)
            }.toString()
        } catch (e: Exception) {
            "{\"gid\":$gid}"
        }
    }
}
