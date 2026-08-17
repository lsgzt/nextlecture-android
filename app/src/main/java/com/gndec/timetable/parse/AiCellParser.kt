package com.gndec.timetable.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AiFields(
    val subject: String?,
    val teacher: String?,
    val venue: String?,
    val lectureType: String?
)

/** Final normalized fields for one lecture (deterministic + AI merged). */
data class LectureFields(
    val subject: String?,
    val teacher: String?,
    val venue: String?,
    val lectureType: String?
)

object AiCellParser {

    private val ALLOWED_KEYS = setOf("subject", "teacher", "venue", "lecture_type")
    private const val MAX_FIELD = 120

    const val SYSTEM_PROMPT =
        "You normalize a single college timetable cell into structured JSON. " +
        "Respond with ONLY a JSON object with exactly these keys: " +
        "{\"subject\": string|null, \"teacher\": string|null, \"venue\": string|null, \"lecture_type\": string|null}. " +
        "Never invent information; use null when uncertain; preserve original naming; " +
        "distinguish rooms (like F106, S205, or LAB names) from teacher names; " +
        "lecture_type must be one of Lecture, Practical, Tutorial or null."

    /**
     * Parse + validate an AI JSON response.
     * @return null when the output is invalid JSON, not an object, or contains
     *         hallucinated/unknown keys (rejected as a whole).
     */
    fun parse(raw: String): AiFields? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val el = try { Json.parseToJsonElement(cleaned) } catch (e: Exception) { return null }
        if (el !is JsonObject) return null
        if (el.keys.any { it !in ALLOWED_KEYS }) return null
        return AiFields(
            subject = str(el, "subject"),
            teacher = str(el, "teacher"),
            venue = str(el, "venue"),
            lectureType = normalizeType(str(el, "lecture_type"))
        )
    }

    private fun str(obj: JsonObject, key: String): String? {
        val p = obj[key] as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.trim().takeIf { it.isNotEmpty() }?.take(MAX_FIELD)
    }

    fun normalizeType(t: String?): String? = when (t?.trim()?.lowercase()) {
        null -> null
        "lecture", "l" -> "Lecture"
        "practical", "p", "lab", "practical/lab" -> "Practical"
        "tutorial", "t" -> "Tutorial"
        else -> null
    }

    fun typeFromTag(tag: String?): String? = normalizeType(tag)

    /**
     * Deterministic fields stay authoritative where present; AI only fills the gaps.
     */
    fun merge(raw: RawLecture, ai: AiFields?): LectureFields = LectureFields(
        subject = raw.subjectHint ?: ai?.subject,
        teacher = raw.teacherHint ?: ai?.teacher,
        venue = raw.venueHint ?: ai?.venue,
        lectureType = typeFromTag(raw.typeTag) ?: ai?.lectureType
    )

    /** Cache key: hash of raw cell text + parser version. */
    fun cacheKey(rawText: String): String =
        TimetableParser.sha256("${TimetableParser.PARSER_VERSION}|$rawText")
}
