package com.gndec.timetable.domain

import com.gndec.timetable.data.db.AiCacheDao
import com.gndec.timetable.data.db.AiCacheEntity
import com.gndec.timetable.net.AiClients
import com.gndec.timetable.net.AiProvider
import com.gndec.timetable.net.BackendClient
import com.gndec.timetable.parse.AiCellParser
import com.gndec.timetable.parse.AiFields
import com.gndec.timetable.parse.LectureFields
import com.gndec.timetable.parse.RawLecture

/**
 * Hybrid normalization: deterministic parser results stay authoritative;
 * the selected AI provider is consulted ONLY for ambiguous cells (low confidence), and only
 * when AI is enabled and a route (user key or backend) exists.
 * Results are cached by sha256(parserVersion|rawCell) so the same cell is
 * never sent to the provider twice. Any AI failure keeps the deterministic fields —
 * the lecture itself is never dropped.
 */
class AiNormalizer(
    private val cache: AiCacheDao,
    private val clients: AiClients,
    private val backend: BackendClient
) {
    data class AiRoute(
        val enabled: Boolean,
        val provider: AiProvider,  // the backend the user configured
        val userApiKey: String?,   // present => call the provider directly
        val backendUrl: String,    // used when userApiKey == null
        val model: String
    )

    var aiWasUsed: Boolean = false
        private set

    suspend fun normalizeAll(raw: List<RawLecture>, route: AiRoute): List<LectureFields> {
        aiWasUsed = false
        return raw.map { cell ->
            val ai = if (cell.confidence < CONFIDENCE_THRESHOLD && route.enabled) {
                resolveAi(cell, route)
            } else null
            AiCellParser.merge(cell, ai)
        }
    }

    private suspend fun resolveAi(cell: RawLecture, route: AiRoute): AiFields? {
        val key = AiCellParser.cacheKey(cell.rawText)
        cache.get(key)?.let { return AiFields(it.subject, it.teacher, it.venue, it.lectureType) }
        val fields = try {
            if (route.userApiKey != null) {
                clients[route.provider].normalize(cell.rawText, route.model, route.userApiKey)
            } else if (route.backendUrl.isNotBlank()) {
                backend.normalize(route.backendUrl, cell.rawText, route.model)
            } else {
                null
            }
        } catch (e: Exception) {
            null // provider/backend down, 429, 5xx, timeout, model removed: keep deterministic data
        }
        if (fields != null) {
            aiWasUsed = true
            cache.put(
                AiCacheEntity(
                    rawHash = key,
                    subject = fields.subject,
                    teacher = fields.teacher,
                    venue = fields.venue,
                    lectureType = fields.lectureType,
                    model = route.model,
                    parsedAt = System.currentTimeMillis()
                )
            )
        }
        return fields
    }

    companion object { private const val CONFIDENCE_THRESHOLD = 0.6 }
}
