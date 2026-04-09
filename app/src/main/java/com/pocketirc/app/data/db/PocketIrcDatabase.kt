package com.pocketirc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatLineEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PocketIrcDatabase : RoomDatabase() {
    abstract fun chatLines(): ChatLineDao

    companion object {
        @Volatile private var INSTANCE: PocketIrcDatabase? = null

        fun get(context: Context): PocketIrcDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketIrcDatabase::class.java,
                    "pocketirc.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
