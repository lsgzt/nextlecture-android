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
 * Android alarms do NOT survive a reboot, an app replacement, or a timezone change:
 * every trigger we store is an absolute instant resolved against the zone that was
 * current at schedule time. After any of those events we reload the locally cached
 * timetable from Room and reschedule all reminders, so the user never has to reopen
 * the app manually. The daily-quote alarm is re-armed here for the same reason: it is an
 * exact one-shot trigger, and losing it would silently freeze the quote at yesterday's.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as TimetableApp
                // The daily quote alarm is exact and one-shot, so a reboot drops it just as
                // it drops lecture reminders. Re-arm before the rest: it costs one call and
                // needs no timetable data, so it works even for a not-yet-configured student.
                runCatching { app.container.quoteScheduler.scheduleNext() }
                val cfg = app.container.settings.flow.first()
                val group = cfg.group
                if (group != null && app.container.db.lectureDao().countForGroup(group) > 0) {
                    // Holiday-aware: a full holiday gets no reminders after a reboot either.
                    app.container.scheduler.rescheduleAll(
                        app.container.db, group, ReminderConfig.from(cfg),
                        com.gndec.timetable.domain.HolidayGate.decode(cfg.holidayJson)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * HTC's documented fast-boot action. Vendors that fast-boot instead of
         * emitting BOOT_COMPLETED would otherwise leave the student with zero
         * reminders after every restart until they open the app by hand.
         *
         * This is deliberately NOT a claim of universal OEM coverage: some ROMs
         * still send neither broadcast, and no undocumented actions are registered
         * here. The startup alarm self-heal in MainActivity is the backstop for those.
         */
        private const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"

        val RESCHEDULE_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Zone change also covers DST transitions, which shift lecture times.
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            HTC_QUICKBOOT_POWERON
        )
    }
}
