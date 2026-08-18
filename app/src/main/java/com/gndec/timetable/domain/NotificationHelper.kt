package com.gndec.timetable.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gndec.timetable.MainActivity
import com.gndec.timetable.R
import com.gndec.timetable.util.Formatters

object NotificationHelper {

    // v2 forces Android to create a fresh channel so existing muted/old channels cannot suppress the bundled sound.
    const val CHANNEL_REMINDERS = "lecture_reminders_v3"
    const val CHANNEL_UPDATES = "timetable_updates_v2"
    private const val TEST_NOTIFICATION_ID = 190816
    private const val APP_UPDATE_NOTIFICATION_ID = 190817
    const val GROUP_LECTURE_REMINDERS = "gndec_lecture_reminders"

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
        ).apply {
            description = "Announcements and app updates"
            setSound(sound, attrs)
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(reminders, updates))
    }

    private fun lectureSound(context: Context): Uri = Uri.parse(
        "android.resource://${context.packageName}/${R.raw.lecture_reminder}"
    )

    fun showAnnouncement(context: Context, announcementId: String, title: String, message: String) {
        if (!notificationsEnabled(context)) return
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            announcementId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(announcementId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS is disabled at runtime.
        }
    }

    fun showAppUpdate(context: Context, latestMarker: String, releaseName: String) {
        if (!notificationsEnabled(context)) return
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            APP_UPDATE_NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseUpdateManager.DOWNLOAD_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = "A newer app release is available. Tap to download release $latestMarker."
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(releaseName.ifBlank { "GNDEC Timetable update $latestMarker" })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(APP_UPDATE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS is disabled at runtime.
        }
    }

    fun notificationsEnabled(context: Context): Boolean {
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return appNotificationsEnabled && runtimePermissionGranted
    }

    /**
     * Keep the notification shade focused on the newest timetable event.
     * Matching by title as well as group also cleans reminders created by
     * older app versions that did not yet assign a notification group.
     */
    private fun clearOlderActiveReminders(context: Context, keepId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { status ->
                status.id != keepId &&
                    status.notification.channelId == CHANNEL_REMINDERS &&
                    status.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.let { title ->
                        title.startsWith("📚") || title.startsWith("🌿")
                    } == true
            }
            .forEach { status -> manager.cancel(status.tag, status.id) }
    }

    /** Post the delayed settings test notification. */
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
            .setSmallIcon(R.drawable.ic_notification)
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

    /**
     * Post a lecture reminder. The caller reuses one notification ID for every
     * stage of the same lecture, so countdowns are replaced rather than stacked.
     * A new lecture also clears any older lecture/free-period reminder.
     */
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
            context, notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        clearOlderActiveReminders(context, notificationId)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setSound(lectureSound(context))
            .setContentTitle(title)
            .setContentText(body.replace("\n", "  •  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(GROUP_LECTURE_REMINDERS)
            .setSortKey("lecture_${notificationId}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime — nothing else we can do locally
        }
    }

    /** Post one notification at the beginning of a real gap between classes. */
    fun showFreePeriodNotification(
        context: Context,
        startMinutes: Int,
        endMinutes: Int,
        nextSubject: String,
        nextStartMinutes: Int,
        nextVenue: String,
        notificationId: Int
    ) {
        if (!notificationsEnabled(context)) return
        val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(0)
        val durationText = when {
            durationMinutes % 60 == 0 -> "${durationMinutes / 60}h"
            durationMinutes > 60 -> "${durationMinutes / 60}h ${durationMinutes % 60}m"
            else -> "${durationMinutes}m"
        }
        val title = "🌿 Your next $durationText is free"
        val body = buildString {
            append("🕐 ${Formatters.range(startMinutes, endMinutes)}")
            append("\nNext: $nextSubject at ${Formatters.hm(nextStartMinutes)}")
            if (nextVenue.isNotBlank()) append("\n📍 $nextVenue")
        }
        val openIntent = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        clearOlderActiveReminders(context, notificationId)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setSound(lectureSound(context))
            .setContentTitle(title)
            .setContentText("Next: $nextSubject at ${Formatters.hm(nextStartMinutes)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_LECTURE_REMINDERS)
            .setSortKey("free_${notificationId}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime.
        }
    }
}
