package com.boopathi.bg.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.boopathi.bg.data.local.dao.ConversationMessageDao
import com.boopathi.bg.data.local.dao.MemoryDao
import com.boopathi.bg.data.local.dao.OwnerProfileDao
import com.boopathi.bg.data.local.dao.TaskDao
import com.boopathi.bg.data.local.dao.WorkflowHistoryDao
import com.boopathi.bg.data.local.entity.ConversationMessageEntity
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.OwnerProfileEntity
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity

@Database(
    entities = [
        OwnerProfileEntity::class,
        ConversationMessageEntity::class,
        MemoryEntity::class,
        TaskEntity::class,
        WorkflowHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class BGDatabase : RoomDatabase() {

    abstract fun ownerProfileDao(): OwnerProfileDao
    abstract fun conversationMessageDao(): ConversationMessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun workflowHistoryDao(): WorkflowHistoryDao

    companion object {
        const val DATABASE_NAME = "bg_database.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `workflow_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `workflowId` TEXT NOT NULL,
                        `goalSummary` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `stepIndex` INTEGER NOT NULL,
                        `totalSteps` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER,
                        `resumable` INTEGER NOT NULL,
                        `failureReason` TEXT
                    )""".trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: BGDatabase? = null

        fun getInstance(context: Context): BGDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BGDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
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

