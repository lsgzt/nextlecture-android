package com.gndec.timetable.domain

import kotlinx.serialization.Serializable

/**
 * One entry of the college's official holiday list. The feed comes from the
 * shared backend cache (see HolidayManager) and carries the college's own
 * category wording: "Public holiday", "Restricted holiday", "Half-day holiday".
 */
@Serializable
data class Holiday(
    val id: String,
    val name: String,
    val date: String,
    val displayDate: String,
    val weekday: String,
    val category: String,
    val year: Int,
    val source: String = ""
)
