package com.gndec.timetable.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gndec.timetable.domain.AlarmScheduler
import com.gndec.timetable.domain.NotificationHelper

/**
 * Fired by AlarmManager at reminder time. Everything needed for the
 * notification is carried in the intent extras (snapshotted at schedule time),
 * so NO network access and NO app process is required here — fully offline.
 */
class LectureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        if (intent.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false)) {
            NotificationHelper.showTestNotification(context)
            return
        }
        val start = intent.getIntExtra(EXTRA_START, -1)
        if (start < 0) return
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "Lecture"
        val end = intent.getIntExtra(EXTRA_END, start + 60)
        val venue = intent.getStringExtra(EXTRA_VENUE) ?: ""
        val teacher = intent.getStringExtra(EXTRA_TEACHER) ?: ""
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "BEFORE_15"
        val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 15)
        val group = intent.getStringExtra(EXTRA_GROUP) ?: ""
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, 0L)
        val notifId = AlarmScheduler.alarmRequestCode(group, epochDay, start, type)
        NotificationHelper.showLectureReminder(
            context, subject, start, end, venue, teacher, type, minutesBefore, notifId
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
        const val EXTRA_TEST_NOTIFICATION = "test_notification"
    }
}
