package com.boopathi.bg

import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Side-effect-limited integration checks suitable for an emulator or test phone. */
@RunWith(AndroidJUnit4::class)
class Phase11DeviceIntegrationTest {

    @Test
    fun appLaunchesAndUsesExpectedPackageAndBackupPolicy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals("com.boopathi.bg", context.packageName)
        assertFalse("Application backup must remain disabled", info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertFalse("Cleartext traffic must remain disabled", info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals("com.boopathi.bg", activity.packageName)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun reminderRecordSurvivesDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "phase11-device-${System.nanoTime()}.db"
        val databaseFile = File(context.noBackupFilesDir, name)
        var taskId = 0L
        try {
            val database = Room.databaseBuilder(context, BGDatabase::class.java, name).build()
            try {
                taskId = database.taskDao().insertTask(
                    TaskEntity(title = "Phase 11 disposable reminder", taskType = TaskEntity.TYPE_REMINDER)
                )
                assertTrue(taskId > 0)
            } finally {
                database.close()
            }

            val reopened = Room.databaseBuilder(context, BGDatabase::class.java, name).build()
            try {
                val stored = reopened.taskDao().getById(taskId)
                assertNotNull(stored)
                assertEquals("Phase 11 disposable reminder", stored?.title)
                assertEquals(TaskEntity.TYPE_REMINDER, stored?.taskType)
            } finally {
                reopened.close()
            }
        } finally {
            databaseFile.delete()
            File(databaseFile.path + "-shm").delete()
            File(databaseFile.path + "-wal").delete()
        }
    }
}
