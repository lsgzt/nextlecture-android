package com.gndec.timetable.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.ZenQuoteClient
import com.gndec.timetable.receiver.QuoteAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The quote on screen, together with the IST day it was fetched for.
 *
 * [dayKey] is what makes "once a day" enforceable: the fetch that the 05:32 alarm triggers
 * stamps the day it ran on, and a later call for the same day becomes a no-op.
 */
data class DailyQuote(val text: String, val author: String, val dayKey: String)

/**
 * Day/clock arithmetic for the daily quote refresh.
 *
 * Deliberately free of Android types so the boundary rules can be unit-tested on the JVM.
 *
 * The boundary is fixed at 05:32 **Asia/Kolkata** rather than the device's own zone. IST
 * has no daylight saving, so the instant is stable year-round, and a student who is abroad
 * or on a mis-set device still gets the refresh at the same moment as everyone else.
 */
object DailyQuoteSchedule {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val FETCH_TIME: LocalTime = LocalTime.of(5, 32)

    /** The IST calendar date an instant falls on, as `yyyy-MM-dd`. */
    fun dayKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().toString()

    /** Epoch millis of 05:32 IST on [date]. */
    fun triggerAt(date: LocalDate): Long =
        date.atTime(FETCH_TIME).atZone(ZONE).toInstant().toEpochMilli()

    /**
     * The next 05:32 IST boundary to fire. Strictly after [nowMillis]: once a day's
     * trigger has passed the answer is tomorrow's, so a receiver that re-arms itself after
     * the alarm it just handled does not schedule the boundary it has already consumed.
     */
    fun nextTriggerAfter(nowMillis: Long): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZONE).toLocalDate()
        val todayTrigger = triggerAt(today)
        return if (nowMillis < todayTrigger) todayTrigger else triggerAt(today.plusDays(1))
    }

    /**
     * Whether a fetch is still owed.
     *
     * True when nothing is cached yet, or when today's 05:32 has already passed and the
     * cached quote is not today's. That second case is the catch-up for a device that was
     * switched off or in a Doze window at 05:32 — the one-shot alarm is simply lost, and
     * the quote has to be pulled the next time the app is opened. The "and not today's"
     * clause is what keeps this at one fetch per day: opening the app fifty times after
     * 05:32 fetches once, then stays quiet until tomorrow.
     */
    fun isFetchDue(cachedDayKey: String, nowMillis: Long): Boolean {
        if (cachedDayKey.isBlank()) return true
        if (cachedDayKey == dayKey(nowMillis)) return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZONE).toLocalDate()
        return nowMillis >= triggerAt(today)
    }
}

/**
 * Holds the daily quote and enforces the once-a-day fetch.
 *
 * Every entry point is mutex-guarded because two of them can race: the 05:32 alarm and the
 * startup catch-up may both run against a fresh day, and the mutex plus the
 * [DailyQuote.dayKey] check inside make the second one a no-op rather than a second call.
 */
class DailyQuoteManager(
    private val settings: SettingsManager,
    private val client: ZenQuoteClient
) {
    private val mutex = Mutex()
    private val _quote = MutableStateFlow<DailyQuote?>(null)
    val quote: StateFlow<DailyQuote?> = _quote.asStateFlow()

    /**
     * Shows the cached quote and fetches only if one is actually owed.
     *
     * The cached quote is published even when it belongs to an earlier day: between 00:00
     * and 05:32 IST the previous day's quote is still the most recent one that exists, and
     * blanking the section for those five hours would be a regression, not a refresh.
     */
    suspend fun ensureCurrent() {
        mutex.withLock {
            publishCached()
            val cached = settings.flow.first()
            if (DailyQuoteSchedule.isFetchDue(cached.quoteDayKey, System.currentTimeMillis())) {
                // A failure is swallowed on purpose: the cached quote is already on screen,
                // and the next open (or tomorrow's alarm) tries again.
                runCatching { fetchAndStore() }
            }
        }
    }

    /**
     * Fetches today's quote, unless the alarm was delivered twice and today is already done.
     * Only the 05:32 alarm calls this; the once-a-day guarantee lives in the dayKey check.
     */
    suspend fun refresh() {
        mutex.withLock {
            val cached = settings.flow.first()
            val today = DailyQuoteSchedule.dayKey(System.currentTimeMillis())
            if (cached.quoteDayKey == today && cached.quoteText.isNotBlank()) {
                publishCached()
            } else {
                runCatching { fetchAndStore() }
            }
        }
    }

    private suspend fun fetchAndStore() {
        val fetched = client.today()
        val dayKey = DailyQuoteSchedule.dayKey(System.currentTimeMillis())
        settings.setQuoteCache(fetched.text, fetched.author, dayKey)
        _quote.value = DailyQuote(fetched.text, fetched.author, dayKey)
    }

    private suspend fun publishCached() {
        val cached = settings.flow.first()
        _quote.value = if (cached.quoteText.isBlank()) {
            null
        } else {
            DailyQuote(cached.quoteText, cached.quoteAuthor, cached.quoteDayKey)
        }
    }
}

/**
 * Arms the one-shot alarm that fires [DailyQuoteManager.refresh] at 05:32 IST.
 *
 * The alarm re-arms itself from [QuoteAlarmReceiver], and is re-armed again on boot and on
 * every app open, because an exact alarm is dropped by a reboot and by an app replacement.
 * Recomputing the boundary each time makes that re-arming idempotent: opening the app
 * repeatedly cannot stack alarms, and the trigger always lands on the correct next boundary
 * no matter when the app was last opened.
 */
class QuoteScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun scheduleNext() {
        withContext(Dispatchers.IO) {
            val trigger = DailyQuoteSchedule.nextTriggerAfter(System.currentTimeMillis())
            val intent = Intent(context, QuoteAlarmReceiver::class.java).apply {
                action = ACTION_FETCH
            }
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // setExactAndAllowWhileIdle rather than setAlarmClock: this alarm is not
            // user-visible, and setAlarmClock would leave a permanent alarm icon in the
            // status bar for a background quote fetch.
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            } catch (_: SecurityException) {
                // Exact alarms can be withheld (API 31+ without the permission). The quote is
                // not time-critical, so a 5-minute window is an acceptable degradation — the
                // startup catch-up in DailyQuoteManager covers the day if even that misses.
                runCatching {
                    alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 5 * 60_000L, pending)
                }
            }
        }
    }

    companion object {
        /** Fixed code: re-arming must replace the previous alarm, never add a second one. */
        const val REQUEST_CODE = 0x0D11

        /**
         * A PendingIntent is identified by its action as well as its request code, and the
         * lecture reminders share this app's request-code space. Setting an action means a
         * hash collision with a lecture alarm's code cannot make this PendingIntent overwrite
         * that alarm's extras and break the reminder.
         */
        const val ACTION_FETCH = "com.gndec.timetable.action.FETCH_DAILY_QUOTE"
    }
}
