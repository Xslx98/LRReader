package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONException
import org.json.JSONObject

/**
 * Entity mapped to table "QUICK_SEARCH".
 */
@Entity(tableName = "QUICK_SEARCH", indices = [Index("TIME")])
class QuickSearch(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    @JvmField
    var id: Long? = null,

    @ColumnInfo(name = "NAME")
    @JvmField
    var name: String? = null,

    @ColumnInfo(name = "MODE")
    @JvmField
    var mode: Int = 0,

    @ColumnInfo(name = "CATEGORY")
    @JvmField
    var category: Int = 0,

    @ColumnInfo(name = "KEYWORD")
    @JvmField
    var keyword: String? = null,

    @ColumnInfo(name = "ADVANCE_SEARCH")
    @JvmField
    var advanceSearch: Int = 0,

    @ColumnInfo(name = "MIN_RATING")
    @JvmField
    var minRating: Int = 0,

    @ColumnInfo(name = "PAGE_FROM")
    @JvmField
    var pageFrom: Int = 0,

    @ColumnInfo(name = "PAGE_TO")
    @JvmField
    var pageTo: Int = 0,

    @ColumnInfo(name = "TIME")
    @JvmField
    var time: Long = 0
) {
    @Ignore
    constructor(id: Long?) : this(id, null, 0, 0, null, 0, 0, 0, 0, 0)

    override fun toString(): String = name ?: ""

    fun toJson(): JSONObject {
        return try {
            JSONObject().apply {
                put("name", name)
                put("mode", mode)
                put("category", category)
                put("keyword", keyword)
                put("advanceSearch", advanceSearch)
                put("minRating", minRating)
                put("pageFrom", pageFrom)
                put("pageTo", pageTo)
                put("time", time)
            }
        } catch (e: JSONException) {
            JSONObject()
        }
    }

    companion object {
        @JvmStatic
        fun quickSearchFromJson(`object`: JSONObject): QuickSearch {
            return QuickSearch().apply {
                name = `object`.optString("name", null)
                mode = `object`.optInt("mode", 0)
                category = `object`.optInt("category", 0)
                keyword = `object`.optString("keyword", null)
                advanceSearch = `object`.optInt("advanceSearch", 0)
                minRating = `object`.optInt("minRating", 0)
                pageFrom = `object`.optInt("pageFrom", 0)
                pageTo = `object`.optInt("pageTo", 0)
                time = `object`.optLong("time", 0)
            }
        }
    }
}
