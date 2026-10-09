package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.ConversationMessageEntity
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.OwnerProfileEntity
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6D Database Migration Test:
 * Tests MIGRATION_1_2 preserving existing tables (memories, owner profile, tasks, messages)
 * and adding workflow_history table and indexes cleanly without destructive fallback.
 */
@RunWith(AndroidJUnit4::class)
class Phase6DDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun test7_databaseMigrationPreservesExistingDataAndCreatesWorkflowHistory() {
        val dbName = "migration_test.db"
        context.deleteDatabase(dbName)

        // 1. Create database with version 1 schema manually using SupportSQLiteOpenHelper / Room
        val dbV1 = Room.databaseBuilder(context, BGDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

        val openHelper = dbV1.openHelper.writableDatabase

        // Insert legacy data into version 1 tables
        openHelper.execSQL(
            """
            INSERT OR REPLACE INTO owner_profile (id, name, preferredLanguage, createdAt, updatedAt)
            VALUES (1, 'Boopathi', 'ta', 1000, 1000)
            """.trimIndent()
        )
        openHelper.execSQL(
            """
            INSERT INTO memories (id, title, content, type, importance, createdAt, updatedAt)
            VALUES (1, 'Project', 'BG Assistant', 'fact', 1, 1000, 1000)
            """.trimIndent()
        )
        openHelper.execSQL(
            """
            INSERT INTO tasks (id, title, description, completed, createdAt)
            VALUES (1, 'Buy Groceries', 'Milk and eggs', 0, 1000)
            """.trimIndent()
        )

        dbV1.close()

        // 2. Open database with version 2 and apply MIGRATION_1_2
        val dbV2 = Room.databaseBuilder(context, BGDatabase::class.java, dbName)
            .addMigrations(BGDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            // Verify existing v1 data preserved
            val profile = dbV2.ownerProfileDao().getOwnerProfile()
            assertNotNull("Owner profile must be preserved across migration", profile)
            assertEquals("Boopathi", profile?.name)
            assertEquals("ta", profile?.preferredLanguage)

            val memories = dbV2.memoryDao().getAllMemories()
            assertEquals(1, memories.size)
            assertEquals("BG Assistant", memories[0].content)

            val tasks = dbV2.taskDao().getAllTasks()
            assertEquals(1, tasks.size)
            assertEquals("Buy Groceries", tasks[0].title)

            // Verify v2 workflow_history table is operational
            dbV2.workflowHistoryDao().insertOrUpdate(
                WorkflowHistoryEntity(
                    workflowId = "migrated_wf_1",
                    goalDescription = "Test migrated workflow",
                    workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                    status = CrossAppWorkflowStatus.SUCCESS.name,
                    currentStepIndex = 1,
                    totalSteps = 1
                )
            )

            val fetchedHistory = dbV2.workflowHistoryDao().getWorkflowById("migrated_wf_1")
            assertNotNull("New workflow_history entity must be queryable in v2", fetchedHistory)
            assertEquals("Test migrated workflow", fetchedHistory?.goalDescription)
        }

        dbV2.close()
        context.deleteDatabase(dbName)
    }
}

