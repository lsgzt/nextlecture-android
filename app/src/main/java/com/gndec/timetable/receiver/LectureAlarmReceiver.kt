package com.gndec.timetable.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.gndec.timetable.domain.AlarmScheduler
import com.gndec.timetable.domain.NotificationHelper

/**
 * Fired by AlarmManager at reminder time. Everything needed for the
 * notification is carried in the intent extras, so no network access is
 * required and delivery still works when the app process is closed.
 */
class LectureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        if (intent.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false)) {
            NotificationHelper.showTestNotification(context)
            return
        }

        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val notificationManager = NotificationManagerCompat.from(context)
        if (type == AlarmScheduler.FREE_PERIOD_TYPE) {
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            if (notificationId == 0) return
            NotificationHelper.showFreePeriodNotification(
                context = context,
                startMinutes = intent.getIntExtra(EXTRA_START, 0),
                endMinutes = intent.getIntExtra(EXTRA_END, 0),
                nextSubject = intent.getStringExtra(EXTRA_FREE_NEXT_SUBJECT) ?: "Lecture",
                nextStartMinutes = intent.getIntExtra(EXTRA_FREE_NEXT_START, 0),
                nextVenue = intent.getStringExtra(EXTRA_FREE_NEXT_VENUE) ?: "",
                notificationId = notificationId
            )
            return
        }

        val start = intent.getIntExtra(EXTRA_START, -1)
        if (start < 0) return
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "Lecture"
        val end = intent.getIntExtra(EXTRA_END, start + 60)
        val venue = intent.getStringExtra(EXTRA_VENUE) ?: ""
        val teacher = intent.getStringExtra(EXTRA_TEACHER) ?: ""
        val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 15)
        val lectureNotificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            AlarmScheduler.lectureNotificationId(
                intent.getStringExtra(EXTRA_GROUP) ?: "",
                intent.getLongExtra(EXTRA_EPOCH_DAY, 0L),
                start
            )
        )

        // A lecture replaces the free-period card that led into it.
        val previousFreeNotificationId = intent.getIntExtra(EXTRA_PREVIOUS_FREE_NOTIFICATION_ID, 0)
        if (previousFreeNotificationId != 0) notificationManager.cancel(previousFreeNotificationId)

        // Also clean up countdown IDs created by older app versions. This makes
        // the upgrade self-healing instead of leaving stale reminders behind.
        if (type == "AT_START") {
            val group = intent.getStringExtra(EXTRA_GROUP) ?: ""
            val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, 0L)
            listOf("BEFORE_30", "BEFORE_15", "BEFORE_5").forEach { oldType ->
                notificationManager.cancel(AlarmScheduler.alarmRequestCode(group, epochDay, start, oldType))
            }
        }

        // Reusing one ID means BEFORE_30, BEFORE_15, BEFORE_5, and AT_START
        // replace one another instead of accumulating in the notification shade.
        NotificationHelper.showLectureReminder(
            context = context,
            subject = subject,
            startMinutes = start,
            endMinutes = end,
            venue = venue,
            teacher = teacher,
            type = type,
            minutesBefore = minutesBefore,
            notificationId = lectureNotificationId
        )
    }

    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_DOW = "dow"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MINUTES_BEFORE = "minutes_before"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_VENUE = "venue"
        const val EXTRA_TEACHER = "teacher"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_PREVIOUS_FREE_NOTIFICATION_ID = "previous_free_notification_id"
        const val EXTRA_FREE_NEXT_SUBJECT = "free_next_subject"
        const val EXTRA_FREE_NEXT_START = "free_next_start"
        const val EXTRA_FREE_NEXT_VENUE = "free_next_venue"
        const val EXTRA_TEST_NOTIFICATION = "test_notification"
    }
}
