package com.freebuff.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        CustomModelEntity::class,
        SettingEntity::class,
        MemoryEntity::class,
        MemoryEntryEntity::class,
    ],
    version = 4,
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

        /** v4:memory_entries 表(检索式记忆库:长/短期条目 + user_id 隔离)。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_entries (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "userId TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "hits INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_entries_userId ON memory_entries(userId)")
            }
        }

        fun get(context: Context): FreebuffDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FreebuffDatabase::class.java,
                "freebuff.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
