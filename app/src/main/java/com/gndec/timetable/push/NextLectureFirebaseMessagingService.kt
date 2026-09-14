package com.gndec.timetable.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gndec.timetable.domain.NotificationHelper

class NextLectureFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmRegistration.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Background notification messages are displayed by FCM itself. Data-only
        // messages are rendered here, including when the app is foregrounded.
        val type = message.data["type"] ?: return
        val title = message.data["title"] ?: message.notification?.title ?: "NextLecture update"
        val body = message.data["body"] ?: message.notification?.body ?: ""
        if (body.isBlank()) return
        NotificationHelper.showRemoteUpdate(this, type, title, body, message.data["id"].orEmpty())
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
