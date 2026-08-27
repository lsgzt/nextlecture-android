package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AttendanceSessionRequest(
    val installationId: String,
    val profileFingerprint: String,
    val branch: String = "",
    val subsection: String = "",
    val timetableGroup: String = "",
    val displayName: String = "",
    val section: String = ""
)

@Serializable
data class AttendanceSessionResponse(
    val studentId: String,
    val accessToken: String,
    val issuedAt: String? = null
)

@Serializable
data class AttendanceRecord(
    @SerialName("attendance_date") val attendanceDate: String,
    @SerialName("lecture_key") val lectureKey: String,
    val status: String,
    val subject: String = "",
    val teacher: String = "",
    val venue: String = "",
    @SerialName("start_minutes") val startMinutes: Int,
    @SerialName("end_minutes") val endMinutes: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class AttendanceRecordRequest(
    @SerialName("attendanceDate") val attendanceDate: String,
    @SerialName("lectureKey") val lectureKey: String,
    val status: String,
    val subject: String = "",
    val teacher: String = "",
    val venue: String = "",
    @SerialName("startMinutes") val startMinutes: Int,
    @SerialName("endMinutes") val endMinutes: Int
)

@Serializable
data class AttendanceSummary(
    val present: Int = 0,
    val absent: Int = 0,
    @SerialName("markedTotal") val markedTotal: Int = 0,
    val percentage: Double? = null,
    val target: Double = 75.0,
    @SerialName("affordableMisses") val affordableMisses: Int? = null,
    @SerialName("lecturesToAttend") val lecturesToAttend: Int? = null
)

@Serializable
data class LeaderboardRow(
    val rank: Int = 0,
    val name: String = "",
    val percentage: Double = 0.0,
    val present: Int = 0,
    val absent: Int = 0,
    val markedTotal: Int = 0,
    val currentStreak: Int = 0,
    val selfReported: Boolean = true,
    val lastMarkedAt: String? = null
)

@Serializable
data class LeaderboardMe(
    val rank: Int = 0,
    val name: String = "",
    val percentage: Double = 0.0,
    val present: Int = 0,
    val absent: Int = 0,
    val markedTotal: Int = 0,
    val currentStreak: Int = 0,
    val selfReported: Boolean = true,
    val lastMarkedAt: String? = null
)

@Serializable
data class LeaderboardResponse(
    val scope: String = "subsection",
    val scopeValue: String = "",
    val scopeLabel: String = "",
    val participants: Int = 0,
    val rows: List<LeaderboardRow> = emptyList(),
    val me: LeaderboardMe? = null,
    val eligibility: String = ""
)

@Serializable
data class AttendanceResponse(
    val from: String,
    val to: String,
    val records: List<AttendanceRecord> = emptyList(),
    val summary: AttendanceSummary = AttendanceSummary()
)

class AttendanceClient(private val client: okhttp3.OkHttpClient = Net.client) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun createSession(baseUrl: String, request: AttendanceSessionRequest): AttendanceSessionResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/attendance/session") ?: throw IllegalArgumentException("Invalid attendance backend URL")
        val body = json.encodeToString(AttendanceSessionRequest.serializer(), request).toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder().url(url).post(body).build()
        execute(httpRequest, AttendanceSessionResponse.serializer())
    }

    suspend fun list(baseUrl: String, accessToken: String, from: String, to: String, target: Double): AttendanceResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/attendance")?.newBuilder()
            ?.addQueryParameter("from", from)
            ?.addQueryParameter("to", to)
            ?.addQueryParameter("target", target.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid attendance backend URL")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        execute(request, AttendanceResponse.serializer())
    }

    suspend fun leaderboard(baseUrl: String, accessToken: String, scope: String, value: String): LeaderboardResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/attendance/leaderboard")?.newBuilder()
            ?.addQueryParameter("scope", scope)
            ?.addQueryParameter("value", value)
            ?.build() ?: throw IllegalArgumentException("Invalid attendance backend URL")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        execute(request, LeaderboardResponse.serializer())
    }

    suspend fun save(baseUrl: String, accessToken: String, request: AttendanceRecordRequest): AttendanceRecord = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/attendance/records") ?: throw IllegalArgumentException("Invalid attendance backend URL")
        val body = json.encodeToString(AttendanceRecordRequest.serializer(), request).toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").post(body).build()
        val response = execute(httpRequest, AttendanceRecordEnvelope.serializer())
        response.record
    }

    suspend fun remove(baseUrl: String, accessToken: String, date: String, lectureKey: String) = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/attendance/records")?.newBuilder()
            ?.addQueryParameter("date", date)
            ?.addQueryParameter("lectureKey", lectureKey)
            ?.build() ?: throw IllegalArgumentException("Invalid attendance backend URL")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "attendance delete failed")
        }
    }

    private fun buildUrl(baseUrl: String, path: String) = baseUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path)?.build()

    private fun <T> execute(request: Request, serializer: kotlinx.serialization.KSerializer<T>): T {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "attendance request failed")
            val body = response.body?.string() ?: throw HttpException(response.code, "empty attendance response")
            return json.decodeFromString(serializer, body)
        }
    }

    @Serializable
    private data class AttendanceRecordEnvelope(val record: AttendanceRecord)
}
