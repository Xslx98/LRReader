package com.lanraragi.reader.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

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

    /** LANraragi category id filter (schema v31); null = no category. */
    @ColumnInfo(name = "CATEGORY_ID")
    @JvmField
    var categoryId: String? = null,

    /** Display-only name for [categoryId]; back-filled after a categories fetch. */
    @ColumnInfo(name = "CATEGORY_NAME")
    @JvmField
    var categoryName: String? = null,

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
    constructor(id: Long?) : this(id, null, 0, 0, null, null, null, 0, 0, 0, 0, 0)

    /**
     * What the UI shows for this entry. The user-given [name] wins; a name wiped
     * by the v1.21.0 drawer bug falls back to the category name, the category id,
     * then the keyword, so a row never renders as an empty line.
     */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: categoryName?.takeIf { it.isNotBlank() }
            ?: categoryId?.takeIf { it.isNotBlank() }
            ?: keyword?.takeIf { it.isNotBlank() }
            ?: ""

    override fun toString(): String = displayName
}
