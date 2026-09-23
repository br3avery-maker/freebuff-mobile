package com.freebuff.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, CustomModelEntity::class, SettingEntity::class, MemoryEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FreebuffDatabase : RoomDatabase() {
    abstract fun dao(): FreebuffDao

    companion object {
        @Volatile
        private var instance: FreebuffDatabase? = null

        /** v2:messages 增加 toolsJson 列(工具卡片,agent-architecture.md §4)。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3:memories 表(Letta 式核心记忆块持久化)。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "block TEXT NOT NULL PRIMARY KEY, " +
                        "content TEXT NOT NULL, " +
                        "charLimit INTEGER NOT NULL, " +
                        "sort INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): FreebuffDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FreebuffDatabase::class.java,
                "freebuff.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
