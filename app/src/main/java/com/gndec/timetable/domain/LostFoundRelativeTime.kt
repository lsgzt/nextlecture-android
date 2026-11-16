package com.gndec.timetable.domain

/**
 * Relative age rendering for Lost & Found posts: "5 min ago", "yesterday",
 * "3 days ago". Extracted from LostFoundManager (which needs Android's
 * Context and so cannot run in the JVM test harness) into this pure
 * object; the manager delegates here.
 */
object LostFoundRelativeTime {

    /** "2 h ago" / "yesterday" / "3 d ago" for a post's created_at. */
    fun relativeTime(createdAtIso: String, nowMillis: Long = System.currentTimeMillis()): String {
        val instant = runCatching { java.time.OffsetDateTime.parse(createdAtIso) }.getOrNull()
            ?: return ""
        val age = nowMillis - instant.toInstant().toEpochMilli()
        val minutes = age / 60_000
        return when {
            age < 0 -> "just now"
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> {
                val hours = minutes / 60
                "$hours hour${if (hours == 1L) "" else "s"} ago"
            }
            minutes < 60 * 24 * 7 -> {
                val days = minutes / (60 * 24)
                if (days == 1L) "yesterday" else "$days days ago"
            }
            else -> {
                val weeks = minutes / (60 * 24 * 7)
                "$weeks week${if (weeks == 1L) "" else "s"} ago"
            }
        }
    }
}
