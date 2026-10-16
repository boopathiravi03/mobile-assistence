package com.boopathi.bg

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.boopathi.bg.data.local.BGDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduledTaskMigrationTest {
    @Test fun v2MigrationPreservesExistingTasksAndAddsScheduledColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, dueTime INTEGER, completed INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO tasks (id, title, description, dueTime, completed, createdAt) VALUES (7, 'existing task', '', NULL, 0, 1000)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            BGDatabase.MIGRATION_2_3.migrate(db)
            db.query("SELECT title, status, taskType, updatedAt FROM tasks WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing task", cursor.getString(0))
                assertEquals("pending", cursor.getString(1))
                assertEquals("task", cursor.getString(2))
                assertEquals(1000L, cursor.getLong(3))
            }
            db.query("PRAGMA table_info(tasks)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertTrue("recurrenceRule" in columns)
                assertTrue("snoozedUntil" in columns)
                assertTrue("timezoneId" in columns)
            }
        } finally {
            helper.close()
        }
    }
}
