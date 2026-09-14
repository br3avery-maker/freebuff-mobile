package com.freebuff.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [SessionEntity::class, MessageEntity::class, CustomModelEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FreebuffDatabase : RoomDatabase() {
    abstract fun dao(): FreebuffDao

    companion object {
        @Volatile
        private var instance: FreebuffDatabase? = null

        fun get(context: Context): FreebuffDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FreebuffDatabase::class.java,
                "freebuff.db",
            ).build().also { instance = it }
        }
    }
}
