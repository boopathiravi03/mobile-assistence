package com.boopathi.bg.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = RoomBGRepository(BGDatabase.getInstance(app))
                ReminderManager(app, repository).reconcile()
            } catch (error: Exception) {
                Log.w("ReminderBootReceiver", "Reminder recovery failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }
}
