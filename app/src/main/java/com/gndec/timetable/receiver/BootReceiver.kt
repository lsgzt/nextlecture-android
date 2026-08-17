package com.gndec.timetable.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gndec.timetable.TimetableApp
import com.gndec.timetable.domain.ReminderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android alarms do NOT survive a reboot or app replacement. After boot/update we reload the
 * locally cached timetable from Room and reschedule all reminders — the user
 * never has to reopen the app after restarting the phone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as TimetableApp
                val cfg = app.container.settings.flow.first()
                val group = cfg.group
                if (group != null && app.container.db.lectureDao().countForGroup(group) > 0) {
                    app.container.scheduler.rescheduleAll(
                        app.container.db, group, ReminderConfig.from(cfg)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
