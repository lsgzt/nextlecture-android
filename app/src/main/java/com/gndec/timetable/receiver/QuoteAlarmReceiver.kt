package com.gndec.timetable.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gndec.timetable.TimetableApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires once a day at 05:32 IST to pull the day's quote, then re-arms itself.
 *
 * The exact alarm is one-shot, so re-arming happens before the network work: if the process
 * is killed mid-fetch the next day's alarm is already in place. A fetch that dies with the
 * broadcast is not lost permanently — [com.gndec.timetable.domain.DailyQuoteManager.ensureCurrent]
 * fetches the moment the app is next opened.
 */
class QuoteAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as TimetableApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.quoteScheduler.scheduleNext()
                app.container.quoteManager.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}
