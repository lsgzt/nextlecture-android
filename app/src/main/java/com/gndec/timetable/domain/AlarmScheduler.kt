package com.gndec.timetable.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gndec.timetable.data.db.AppDatabase
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.db.ScheduledAlarmEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.receiver.LectureAlarmReceiver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ReminderConfig(
    val remind15: Boolean,
    val remind30: Boolean,
    val remind5: Boolean,
    val remindAtStart: Boolean
) {
    fun types(): List<Pair<String, Int>> = buildList {
        if (remind15) add("BEFORE_15" to 15)
        if (remind30) add("BEFORE_30" to 30)
        if (remind5) add("BEFORE_5" to 5)
        if (remindAtStart) add("AT_START" to 0)
    }

    companion object {
        fun from(s: AppSettings) =
            ReminderConfig(s.remind15, s.remind30, s.remind5, s.remindAtStart)
    }
}

/** A free interval between two actual timetable lectures. */
private data class FreePeriodGap(
    val startMinutes: Int,
    val endMinutes: Int,
    val nextLecture: LectureEntity
) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

/**
 * Schedules local, offline-capable lecture and free-period reminders via AlarmManager.
 * Deterministic PendingIntent request codes prevent duplicate alarms across reschedules.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Cancel every recorded alarm, clear records, then schedule upcoming lectures and gaps. */
    suspend fun rescheduleAll(db: AppDatabase, group: String, config: ReminderConfig) {
        val dao = db.alarmDao()
        dao.getAll().forEach { cancel(it.requestCode) }
        dao.clear()
        scheduleUpcoming(db, group, config)
    }

    /** Schedule reminders for lectures and real gaps in the next [DAYS_AHEAD] days. */
    suspend fun scheduleUpcoming(db: AppDatabase, group: String, config: ReminderConfig) {
        val lectures = db.lectureDao().getForGroup(group)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowMillis = System.currentTimeMillis()
        val toSave = mutableListOf<ScheduledAlarmEntity>()
        val types = config.types()
        // Free-period alerts are independent of the lecture countdown toggles;
        // the user asked to be told about real gaps even when no lecture reminder
        // stage is enabled.
        for (dayOffset in 0 until DAYS_AHEAD) {
            val date = today.plusDays(dayOffset.toLong())
            val dow = date.dayOfWeek.value
            val dayLectures = lectures
                .filter { it.dayOfWeek == dow }
                .sortedBy { it.startMinutes }
            if (dayLectures.isEmpty()) continue

            val freeGaps = findFreeGaps(dayLectures)
            val freeIdByEnd = freeGaps.associate { gap ->
                gap.endMinutes to freeNotificationId(group, date.toEpochDay(), gap.startMinutes)
            }

            for (lec in dayLectures) {
                for ((type, minutesBefore) in types) {
                    val trigger = date.atTime(LocalTime.MIDNIGHT)
                        .plusMinutes((lec.startMinutes - minutesBefore).toLong())
                        .atZone(zone).toInstant().toEpochMilli()
                    if (trigger <= nowMillis) continue

                    val alarmCode = alarmRequestCode(group, date.toEpochDay(), lec.startMinutes, type)
                    val lectureNotificationId = lectureNotificationId(group, date.toEpochDay(), lec.startMinutes)
                    val previousFreeNotificationId = freeIdByEnd[lec.startMinutes] ?: 0
                    val intent = Intent(context, LectureAlarmReceiver::class.java).apply {
                        putExtra(LectureAlarmReceiver.EXTRA_GROUP, group)
                        putExtra(LectureAlarmReceiver.EXTRA_DOW, dow)
                        putExtra(LectureAlarmReceiver.EXTRA_START, lec.startMinutes)
                        putExtra(LectureAlarmReceiver.EXTRA_TYPE, type)
                        putExtra(LectureAlarmReceiver.EXTRA_EPOCH_DAY, date.toEpochDay())
                        putExtra(LectureAlarmReceiver.EXTRA_MINUTES_BEFORE, minutesBefore)
                        putExtra(LectureAlarmReceiver.EXTRA_NOTIFICATION_ID, lectureNotificationId)
                        putExtra(LectureAlarmReceiver.EXTRA_PREVIOUS_FREE_NOTIFICATION_ID, previousFreeNotificationId)
                        // Snapshot the essential display fields so the notification
                        // needs no network and minimal DB work at fire time.
                        putExtra(LectureAlarmReceiver.EXTRA_SUBJECT, lec.subject ?: "Lecture")
                        putExtra(LectureAlarmReceiver.EXTRA_VENUE, lec.venue ?: "")
                        putExtra(LectureAlarmReceiver.EXTRA_TEACHER, lec.teacher ?: "")
                        putExtra(LectureAlarmReceiver.EXTRA_END, lec.endMinutes)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, alarmCode, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (scheduleWithBestAvailableAlarm(trigger, pi, alarmCode)) {
                        toSave.add(
                            ScheduledAlarmEntity(alarmCode, group, date.toEpochDay(), lec.startMinutes, type, trigger)
                        )
                    }
                }
            }

            // Free notifications are intentionally only for gaps between lectures.
            // This avoids noisy "free" alerts before the first class or after the last class.
            for (gap in freeGaps) {
                val trigger = date.atTime(LocalTime.MIDNIGHT)
                    .plusMinutes(gap.startMinutes.toLong())
                    .atZone(zone).toInstant().toEpochMilli()
                if (trigger <= nowMillis) continue

                val alarmCode = alarmRequestCode(group, date.toEpochDay(), gap.startMinutes, FREE_PERIOD_TYPE)
                val notificationId = freeNotificationId(group, date.toEpochDay(), gap.startMinutes)
                val next = gap.nextLecture
                val intent = Intent(context, LectureAlarmReceiver::class.java).apply {
                    putExtra(LectureAlarmReceiver.EXTRA_GROUP, group)
                    putExtra(LectureAlarmReceiver.EXTRA_DOW, dow)
                    putExtra(LectureAlarmReceiver.EXTRA_START, gap.startMinutes)
                    putExtra(LectureAlarmReceiver.EXTRA_END, gap.endMinutes)
                    putExtra(LectureAlarmReceiver.EXTRA_TYPE, FREE_PERIOD_TYPE)
                    putExtra(LectureAlarmReceiver.EXTRA_EPOCH_DAY, date.toEpochDay())
                    putExtra(LectureAlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                    putExtra(LectureAlarmReceiver.EXTRA_FREE_NEXT_SUBJECT, next.subject ?: "Lecture")
                    putExtra(LectureAlarmReceiver.EXTRA_FREE_NEXT_START, next.startMinutes)
                    putExtra(LectureAlarmReceiver.EXTRA_FREE_NEXT_VENUE, next.venue ?: "")
                }
                val pi = PendingIntent.getBroadcast(
                    context, alarmCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (scheduleWithBestAvailableAlarm(trigger, pi, alarmCode)) {
                    toSave.add(
                        ScheduledAlarmEntity(alarmCode, group, date.toEpochDay(), gap.startMinutes, FREE_PERIOD_TYPE, trigger)
                    )
                }
            }
        }
        db.alarmDao().putAll(toSave)
    }

    private fun findFreeGaps(dayLectures: List<LectureEntity>): List<FreePeriodGap> {
        if (dayLectures.size < 2) return emptyList()
        val gaps = mutableListOf<FreePeriodGap>()
        var previousEnd = dayLectures.first().endMinutes
        for (lecture in dayLectures.drop(1)) {
            val gapStart = previousEnd
            val gapEnd = lecture.startMinutes
            if (gapEnd - gapStart >= MIN_FREE_PERIOD_MINUTES) {
                gaps += FreePeriodGap(gapStart, gapEnd, lecture)
            }
            previousEnd = maxOf(previousEnd, lecture.endMinutes)
        }
        return gaps
    }

    fun scheduleTestNotification(delayMinutes: Int): Boolean {
        val trigger = System.currentTimeMillis() + delayMinutes.coerceIn(1, 60) * 60_000L
        val intent = Intent(context, LectureAlarmReceiver::class.java).apply {
            putExtra(LectureAlarmReceiver.EXTRA_TEST_NOTIFICATION, true)
        }
        val pi = PendingIntent.getBroadcast(
            context, TEST_NOTIFICATION_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return scheduleWithBestAvailableAlarm(trigger, pi, TEST_NOTIFICATION_REQUEST_CODE)
    }

    private fun scheduleWithBestAvailableAlarm(trigger: Long, pi: PendingIntent, requestCode: Int): Boolean {
        return try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode xor Int.MIN_VALUE,
                    Intent(context, com.gndec.timetable.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), pi)
            }
            true
        } catch (_: SecurityException) {
            try {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 5 * 60_000L, pi)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    fun cancel(requestCode: Int) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, LectureAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) alarmManager.cancel(pi)
    }

    fun canScheduleExact(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    companion object {
        const val DAYS_AHEAD = 14
        const val TEST_NOTIFICATION_REQUEST_CODE = 0x5EED
        const val FREE_PERIOD_TYPE = "FREE_PERIOD"
        const val MIN_FREE_PERIOD_MINUTES = 30

        /** Deterministic PendingIntent id for one lecture reminder alarm. */
        fun alarmRequestCode(group: String, epochDay: Long, startMinutes: Int, reminderType: String): Int {
            var h = 17
            h = 31 * h + group.hashCode()
            h = 31 * h + (epochDay % 100_000).toInt()
            h = 31 * h + startMinutes
            h = 31 * h + reminderType.hashCode()
            return h and Int.MAX_VALUE
        }

        /** One stable notification id is reused for BEFORE_15/30/5 and AT_START. */
        fun lectureNotificationId(group: String, epochDay: Long, startMinutes: Int): Int =
            alarmRequestCode(group, epochDay, startMinutes, "LECTURE_NOTIFICATION")

        fun freeNotificationId(group: String, epochDay: Long, startMinutes: Int): Int =
            alarmRequestCode(group, epochDay, startMinutes, "FREE_NOTIFICATION")
    }
}
