package com.gndec.timetable.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lectures")
data class LectureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupName: String,
    /** 1 = Monday ... 7 = Sunday (java.time.DayOfWeek.value) */
    val dayOfWeek: Int,
    /** minutes since midnight */
    val startMinutes: Int,
    val endMinutes: Int,
    val subject: String?,
    val teacher: String?,
    val venue: String?,
    val lectureType: String?,
    val rawText: String,
    val fetchId: Long
)

@Entity(tableName = "timetable_meta")
data class TimetableMetaEntity(
    @PrimaryKey val id: Int = 1,
    val sourceUrl: String?,
    /** last time we downloaded AND successfully parsed+validated+saved a timetable */
    val lastSuccessfulFetch: Long?,
    /** last time we attempted a check (regardless of result) */
    val lastChecked: Long?,
    val etag: String?,
    val lastModified: String?,
    val timetableHash: String?
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    /** sha256("$PARSER_VERSION|$rawText") */
    @PrimaryKey val rawHash: String,
    val subject: String?,
    val teacher: String?,
    val venue: String?,
    val lectureType: String?,
    val model: String?,
    val parsedAt: Long
)

@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    /** deterministic PendingIntent request code */
    @PrimaryKey val requestCode: Int,
    val groupName: String,
    /** LocalDate.toEpochDay() of the lecture date */
    val epochDay: Long,
    val startMinutes: Int,
    val reminderType: String,
    val epochMillis: Long
)

@Entity(tableName = "syllabus_chat_sessions")
data class SyllabusChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val branch: String
)

@Entity(
    tableName = "syllabus_chat_messages",
    indices = [Index(value = ["sessionId", "timestamp"])]
)
data class SyllabusChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** Gemini-compatible role: user or model. */
    val role: String,
    val content: String,
    val timestamp: Long
)
