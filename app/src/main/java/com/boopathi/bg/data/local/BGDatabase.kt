package com.boopathi.bg.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.boopathi.bg.data.local.dao.ConversationMessageDao
import com.boopathi.bg.data.local.dao.MemoryDao
import com.boopathi.bg.data.local.dao.OwnerProfileDao
import com.boopathi.bg.data.local.dao.TaskDao
import com.boopathi.bg.data.local.entity.ConversationMessageEntity
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.OwnerProfileEntity
import com.boopathi.bg.data.local.entity.TaskEntity

@Database(
    entities = [
        OwnerProfileEntity::class,
        ConversationMessageEntity::class,
        MemoryEntity::class,
        TaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BGDatabase : RoomDatabase() {

    abstract fun ownerProfileDao(): OwnerProfileDao
    abstract fun conversationMessageDao(): ConversationMessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao

    companion object {
        const val DATABASE_NAME = "bg_database.db"

        @Volatile
        private var INSTANCE: BGDatabase? = null

        fun getInstance(context: Context): BGDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BGDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun createInMemory(context: Context): BGDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                BGDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}

