/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.text.TextUtils
import android.util.Log
import com.hippo.util.SqlUtils
import java.util.LinkedList

class SearchDatabase private constructor(context: Context) {

    private val mDatabase: SQLiteDatabase

    init {
        val databaseHelper = DatabaseHelper(context)
        mDatabase = databaseHelper.writableDatabase
    }

    fun getSuggestions(prefix: String, limit: Int): Array<String> {
        val queryList = LinkedList<String>()
        val actualLimit = maxOf(0, limit)

        val sb = StringBuilder()
        sb.append("SELECT * FROM ").append(TABLE_SUGGESTIONS)
        if (!TextUtils.isEmpty(prefix)) {
            sb.append(" WHERE ").append(COLUMN_QUERY).append(" LIKE '")
                .append(SqlUtils.sqlEscapeString(prefix)).append("%'")
        }
        sb.append(" ORDER BY ").append(COLUMN_DATE).append(" DESC")
            .append(" LIMIT ").append(actualLimit)

        try {
            val cursor = mDatabase.rawQuery(sb.toString(), null)
            val queryIndex = cursor.getColumnIndex(COLUMN_QUERY)
            if (cursor.moveToFirst()) {
                while (!cursor.isAfterLast) {
                    val suggestion = cursor.getString(queryIndex)
                    if (prefix != suggestion) {
                        queryList.add(suggestion)
                    }
                    cursor.moveToNext()
                }
            }
            cursor.close()
            return queryList.toTypedArray()
        } catch (e: SQLException) {
            return emptyArray()
        }
    }

    fun addQuery(query: String) {
        if (!TextUtils.isEmpty(query)) {
            // Delete old first
            deleteQuery(query)
            // Add it to database
            val values = ContentValues()
            values.put(COLUMN_QUERY, query)
            values.put(COLUMN_DATE, System.currentTimeMillis())
            mDatabase.insert(TABLE_SUGGESTIONS, null, values)
            // Remove history if more than max
            truncateHistory(MAX_HISTORY)
        }
    }

    fun deleteQuery(query: String) {
        mDatabase.delete(TABLE_SUGGESTIONS, "$COLUMN_QUERY=?", arrayOf(query))
    }

    fun clearQuery() {
        truncateHistory(0)
    }

    /**
     * Reduces the length of the history table, to prevent it from growing too large.
     *
     * @param maxEntries Max entries to leave in the table. 0 means remove all entries.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun truncateHistory(maxEntries: Int) {
        require(maxEntries >= 0)

        try {
            // null means "delete all".  otherwise "delete but leave n newest"
            val selection = if (maxEntries > 0) {
                "_id IN " +
                    "(SELECT _id FROM $TABLE_SUGGESTIONS" +
                    " ORDER BY $COLUMN_DATE DESC" +
                    " LIMIT -1 OFFSET $maxEntries)"
            } else {
                null
            }
            mDatabase.delete(TABLE_SUGGESTIONS, selection, null)
        } catch (e: RuntimeException) {
            Log.e(TAG, "truncateHistory", e)
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_SUGGESTIONS (" +
                    "_id INTEGER PRIMARY KEY" +
                    ",$COLUMN_QUERY TEXT" +
                    ",$COLUMN_DATE LONG" +
                    ");"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SUGGESTIONS")
            onCreate(db)
        }
    }

    companion object {
        private val TAG = SearchDatabase::class.java.simpleName

        const val COLUMN_QUERY = "query"
        const val COLUMN_DATE = "date"

        private const val DATABASE_NAME = "search_database.db"
        private const val TABLE_SUGGESTIONS = "suggestions"

        private const val MAX_HISTORY = 100

        @Volatile
        private var sInstance: SearchDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): SearchDatabase {
            if (sInstance == null) {
                synchronized(SearchDatabase::class.java) {
                    if (sInstance == null) {
                        sInstance = SearchDatabase(context.applicationContext)
                    }
                }
            }
            return sInstance!!
        }
    }
}
