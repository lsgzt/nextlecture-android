package com.gndec.timetable.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gndec.timetable.domain.NotificationHelper

class NextLectureFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmRegistration.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Always ensure channels exist so the bundled tone is attached even if the
        // process was cold-started by FCM.
        NotificationHelper.ensureChannels(this)

        // Prefer data payload. When the app is in the foreground, notification
        // payloads are also delivered here — render them ourselves with the
        // bundled sound. Background + notification-payload messages are shown
        // by the system using the default channel declared in the manifest
        // (timetable_updates_v3 → same bundled tone).
        val type = message.data["type"]
            ?: if (message.notification != null) "push" else return
        val title = message.data["title"]
            ?: message.notification?.title
            ?: "NextLecture update"
        val body = message.data["body"]
            ?: message.notification?.body
            ?: return
        if (body.isBlank()) return
        val eventId = message.data["id"].orEmpty().ifBlank {
            message.messageId.orEmpty()
        }
        NotificationHelper.showRemoteUpdate(this, type, title, body, eventId)
    }
}

object FcmRegistration {
    private const val BACKEND_URL = "https://nextlecture.vercel.app"

    fun register(context: android.content.Context, token: String) {
        Thread {
            runCatching { registerToken(context.applicationContext, token) }
        }.start()
    }

    fun registerCurrent(context: android.content.Context) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(context, token) }
    }

    private fun registerToken(context: android.content.Context, token: String) {
        val body: okhttp3.RequestBody = org.json.JSONObject().apply {
            put("token", token)
            put("appVersion", com.gndec.timetable.BuildConfig.VERSION_NAME)
            put("platform", "android")
        }.toString().toRequestBody(com.gndec.timetable.net.Net.JSON_MEDIA)
        val request = okhttp3.Request.Builder()
            .url("$BACKEND_URL/api/notifications/register")
            .post(body)
            .header("User-Agent", "NextLecture/${com.gndec.timetable.BuildConfig.VERSION_NAME}")
            .build()
        com.gndec.timetable.net.Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("FCM registration failed: ${response.code}")
        }
    }
}

private fun String.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(mediaType, this)
