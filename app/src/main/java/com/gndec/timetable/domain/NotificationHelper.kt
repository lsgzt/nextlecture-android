package com.gndec.timetable.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gndec.timetable.MainActivity
import com.gndec.timetable.R
import com.gndec.timetable.util.Formatters

object NotificationHelper {

    // v2 forces Android to create a fresh channel so existing muted/old channels cannot suppress the bundled sound.
    const val CHANNEL_REMINDERS = "lecture_reminders_v3"
    const val CHANNEL_UPDATES = "timetable_updates"
    private const val TEST_NOTIFICATION_ID = 190816

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val sound: Uri = Uri.parse(
            "android.resource://${context.packageName}/${R.raw.lecture_reminder}"
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_reminders_desc)
            setSound(sound, attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            setShowBadge(true)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannels(listOf(reminders, updates))
    }

    private fun lectureSound(context: Context): Uri = Uri.parse(
        "android.resource://${context.packageName}/${R.raw.lecture_reminder}"
    )

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Post a lecture reminder. Everything needed is passed in —
     * NO network calls happen here (works fully offline).
     */
    fun showTestNotification(context: Context): Boolean {
        if (!notificationsEnabled(context)) return false
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context, TEST_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setSound(lectureSound(context))
            .setContentTitle("GNDEC Timetable notifications work")
            .setContentText("This is a test reminder from your timetable app.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("This is a test reminder from your timetable app. Your lecture reminders are ready to alert you on time."))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun showLectureReminder(
        context: Context,
        subject: String,
        startMinutes: Int,
        endMinutes: Int,
        venue: String,
        teacher: String,
        type: String,
        minutesBefore: Int,
        notificationId: Int
    ) {
        if (!notificationsEnabled(context)) return

        val title = when {
            type == "AT_START" -> "📚 $subject is starting now"
            else -> "📚 $subject starts in $minutesBefore minutes"
        }
        val body = buildString {
            append("🕐 ${Formatters.range(startMinutes, endMinutes)}")
            if (venue.isNotBlank()) append("\n📍 $venue")
            if (teacher.isNotBlank()) append("\n👨‍🏫 $teacher")
        }

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setSound(lectureSound(context))
            .setContentTitle(title)
            .setContentText(body.replace("\n", "  •  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime — nothing else we can do locally
        }
    }
}
