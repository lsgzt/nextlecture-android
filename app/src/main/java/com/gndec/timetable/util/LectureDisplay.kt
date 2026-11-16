package com.gndec.timetable.util

import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings

/**
 * Official student-group timetables often leave teacher and room blank for
 * mentoring slots. Profile already stores mentor name and venue from the
 * permanent section PDF — use those when the cell is a mentoring class.
 */
fun LectureEntity.isMentoringClass(): Boolean {
    val s = subject.orEmpty()
    return s.contains("mentor", ignoreCase = true)
}

/** Timetable teacher, or saved mentor name for mentoring classes. */
fun LectureEntity.resolvedTeacher(mentorName: String): String? {
    teacher?.takeIf { it.isNotBlank() }?.let { return it }
    if (isMentoringClass()) mentorName.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

/** Timetable venue, or saved mentor venue for mentoring classes. */
fun LectureEntity.resolvedVenue(mentorVenue: String): String? {
    venue?.takeIf { it.isNotBlank() }?.let { return it }
    if (isMentoringClass()) mentorVenue.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

fun LectureEntity.resolvedTeacher(settings: AppSettings): String? =
    resolvedTeacher(settings.mentorName)

fun LectureEntity.resolvedVenue(settings: AppSettings): String? =
    resolvedVenue(settings.mentorVenue)

fun LectureEntity.displayTeacher(
    mentorName: String,
    unavailable: String = "Teacher unavailable"
): String = resolvedTeacher(mentorName) ?: unavailable

fun LectureEntity.displayVenue(
    mentorVenue: String,
    unavailable: String = "Venue unavailable"
): String = resolvedVenue(mentorVenue) ?: unavailable
