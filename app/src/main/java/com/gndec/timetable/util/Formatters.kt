package com.gndec.timetable.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun freshnessText(lastSuccessMillis: Long?, nowMillis: Long): String {
        if (lastSuccessMillis == null) return "Timetable not fetched yet"
        val diff = nowMillis - lastSuccessMillis
        if (diff < 0) return "Updated just now"
        val min = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            diff < 60_000 -> "Updated just now"
            min < 60 -> "Updated $min min ago"
            hours < 24 -> "Updated $hours hour${if (hours == 1L) "" else "s"} ago"
            days == 1L -> "Updated yesterday"
            else -> "Updated $days days ago"
        }
    }

    fun isStale(
        lastSuccessMillis: Long?,
        nowMillis: Long,
        thresholdMillis: Long = 36L * 3_600_000
    ): Boolean = lastSuccessMillis == null || nowMillis - lastSuccessMillis > thresholdMillis

    fun hm(minutes: Int): String {
        val h24 = (minutes / 60) % 24
        val m = minutes % 60
        val am = h24 < 12
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return "%d:%02d %s".format(h12, m, if (am) "AM" else "PM")
    }

    fun range(startMin: Int, endMin: Int) = "${hm(startMin)} – ${hm(endMin)}"

    fun countdown(minutes: Int): String = when {
        minutes >= 120 -> "${minutes / 60} hr ${minutes % 60} min"
        minutes >= 60 -> "1 hr ${minutes - 60} min"
        minutes < 0 -> "0 min"
        else -> "$minutes min"
    }

    fun dateTime(millis: Long): String =
        SimpleDateFormat("EEE, d MMM, h:mm a", Locale.getDefault()).format(Date(millis))

    fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "Monday"; 2 -> "Tuesday"; 3 -> "Wednesday"; 4 -> "Thursday"
        5 -> "Friday"; 6 -> "Saturday"; 7 -> "Sunday"; else -> "Day $dayOfWeek"
    }
}
