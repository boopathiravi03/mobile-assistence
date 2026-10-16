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
    version = 3,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'task'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN timezoneId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceRule TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN nextOccurrenceAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN snoozedUntil INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN lastOccurrenceAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN notificationId INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN expiresAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN failureReason TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET status = CASE WHEN completed = 1 THEN 'completed' ELSE 'pending' END, updatedAt = createdAt")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_dueTime ON tasks(dueTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status_dueTime ON tasks(status, dueTime)")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

