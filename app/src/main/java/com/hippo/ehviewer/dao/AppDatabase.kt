package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database replacing GreenDAO's DaoMaster/DaoSession.
 *
 * ## Migration 指南
 *
 * v9 是首个公开发布版本（baseline）。未来修改数据库结构时：
 *
 * 1. 修改 Entity（增删改字段/表）
 * 2. version 加 1（如 9 → 10）
 * 3. 编写 Migration 对象，例如：
 *    ```
 *    private val MIGRATION_9_10 = object : Migration(9, 10) {
 *        override fun migrate(db: SupportSQLiteDatabase) {
 *            db.execSQL("ALTER TABLE ... ADD COLUMN ...")
 *        }
 *    }
 *    ```
 * 4. 在 databaseBuilder 中注册：`.addMigrations(MIGRATION_9_10)`
 *
 * Room 会自动链式执行所需的 Migration（如 v9→v11 = MIGRATION_9_10 + MIGRATION_10_11）。
 * **不要使用 fallbackToDestructiveMigration()，否则用户数据会丢失。**
 */
@Database(
    entities = [
        DownloadInfo::class,
        DownloadLabel::class,
        DownloadDirname::class,
        HistoryInfo::class,
        LocalFavoriteInfo::class,
        QuickSearch::class,
        Filter::class,
        BlackList::class,
        GalleryTags::class,
        BookmarkInfo::class,
        ServerProfile::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadRoomDao
    abstract fun browsingDao(): BrowsingRoomDao
    abstract fun miscDao(): MiscRoomDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "eh.db"
                )
                    // 未来添加 Migration 示例：
                    // .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
