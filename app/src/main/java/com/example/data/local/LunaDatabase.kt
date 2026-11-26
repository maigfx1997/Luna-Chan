package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NovelHistoryEntity::class, CustomSiteEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LunaDatabase : RoomDatabase() {
    abstract fun novelHistoryDao(): NovelHistoryDao
    abstract fun customSiteDao(): CustomSiteDao

    companion object {
        @Volatile
        private var INSTANCE: LunaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_novel_sites` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `useSmartBrowserDefault` INTEGER NOT NULL,
                        `titleSelector` TEXT NOT NULL,
                        `authorSelector` TEXT NOT NULL,
                        `descriptionSelector` TEXT NOT NULL,
                        `coverSelector` TEXT NOT NULL,
                        `chapterListSelector` TEXT NOT NULL,
                        `chapterContentSelector` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): LunaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LunaDatabase::class.java,
                    "luna_chan_history.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
