package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.AttendanceClient
import com.gndec.timetable.net.AttendanceRecord
import com.gndec.timetable.net.AttendanceRecordRequest
import com.gndec.timetable.net.AttendanceResponse
import com.gndec.timetable.net.AttendanceSessionRequest
import com.gndec.timetable.net.HttpException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AttendanceManager(
    private val client: AttendanceClient,
    private val keys: SecureKeyStore,
    private val settings: SettingsManager
) {
    private val sessionMutex = Mutex()
    private var synchronizedFingerprint: String? = null

    /** In-process list cache so Home / Attendance tab switches do not re-hit the network. */
    private val cacheMutex = Mutex()
    private var cachedResponse: AttendanceResponse? = null
    private var cachedKey: String? = null

    suspend fun load(from: LocalDate, to: LocalDate, target: Double, forceRefresh: Boolean = false): AttendanceResponse {
        val cfg = settings.flow.first()
        val key = listCacheKey(cfg, from, to, target)
        if (!forceRefresh) {
            cacheMutex.withLock {
                val hit = cachedResponse
                if (hit != null && cachedKey == key) return hit
            }
        }
        val response = withSession(cfg) { token ->
            client.list(cfg.pyqRagBackendUrl, token, from.toString(), to.toString(), target)
        }
        cacheMutex.withLock {
            cachedResponse = response
            cachedKey = key
        }
        return response
    }

    /** Drop the list cache after the student marks / unmarks a lecture. */
    suspend fun invalidateListCache() {
        cacheMutex.withLock {
            cachedResponse = null
            cachedKey = null
        }
    }

    private fun listCacheKey(
        cfg: com.gndec.timetable.data.prefs.AppSettings,
        from: LocalDate,
        to: LocalDate,
        target: Double
    ): String = listOf(
        from.toString(),
        to.toString(),
        target.toString(),
        cfg.registrationNumber,
        cfg.rollNumber,
        cfg.branch,
        cfg.studentSubsection,
        cfg.studentName,
        cfg.group.orEmpty()
    ).joinToString("|")

    suspend fun mark(date: LocalDate, lecture: LectureEntity, status: String): AttendanceRecord {
        require(status == "present" || status == "absent")
        val cfg = settings.flow.first()
        val record = withSession(cfg) { token ->
            client.save(
                cfg.pyqRagBackendUrl,
                token,
                AttendanceRecordRequest(
                    attendanceDate = date.toString(),
                    lectureKey = lectureKey(date, lecture),
                    status = status,
                    subject = lecture.subject.orEmpty(),
                    teacher = lecture.teacher.orEmpty(),
                    venue = lecture.venue.orEmpty(),
                    startMinutes = lecture.startMinutes,
                    endMinutes = lecture.endMinutes,
                    lectureType = normalizeLectureType(lecture.lectureType)
                )
            )
        }
        invalidateListCache()
        return record
    }

    /**
     * Mark attendance for a lecture that is not on today's published slot — e.g. a
     * teacher swap where Chemistry ran in the Maths period. Builds a stable
     * lecture key from the chosen subject/time so the mark is syncable and clearable.
     */
    suspend fun markManual(
        date: LocalDate,
        groupName: String,
        subject: String,
        lectureType: String,
        startMinutes: Int,
        endMinutes: Int,
        status: String,
        teacher: String = "",
        venue: String = ""
    ): AttendanceRecord {
        require(subject.isNotBlank()) { "Subject is required" }
        require(endMinutes > startMinutes) { "End time must be after start time" }
        val lecture = LectureEntity(
            groupName = groupName,
            dayOfWeek = date.dayOfWeek.value,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            subject = subject.trim(),
            teacher = teacher.trim().ifBlank { null },
            venue = venue.trim().ifBlank { null },
            lectureType = lectureType,
            rawText = "manual-swap",
            fetchId = 0L
        )
        return mark(date, lecture, status)
    }

    suspend fun unmark(date: LocalDate, lecture: LectureEntity) {
        val cfg = settings.flow.first()
        withSession(cfg) { token ->
            client.remove(cfg.pyqRagBackendUrl, token, date.toString(), lectureKey(date, lecture))
        }
        invalidateListCache()
    }

    suspend fun leaderboard(scope: String, value: String = ""): com.gndec.timetable.net.LeaderboardResponse {
        val cfg = settings.flow.first()
        return withSession(cfg) { token ->
            client.leaderboard(cfg.pyqRagBackendUrl, token, scope, value)
        }
    }

    suspend fun currentToken(): String? = keys.getAttendanceToken()

    suspend fun <T> withSession(cfg: com.gndec.timetable.data.prefs.AppSettings, operation: suspend (String) -> T): T {
        val firstToken = ensureSession(cfg)
        return try {
            operation(firstToken)
        } catch (error: HttpException) {
            if (error.code != 401) throw error
            keys.removeAttendanceSession()
            synchronizedFingerprint = null
            operation(ensureSession(cfg))
        }
    }

    private suspend fun ensureSession(cfg: com.gndec.timetable.data.prefs.AppSettings): String = sessionMutex.withLock {
        val fingerprint = profileFingerprint(cfg)
        val installationId = keys.getAttendanceInstallationId()
        val savedToken = keys.getAttendanceToken()
        if (!installationId.isNullOrBlank() && !savedToken.isNullOrBlank() && keys.getAttendanceProfileFingerprint() == fingerprint) {
            if (synchronizedFingerprint == fingerprint) return@withLock savedToken
            val refreshed = client.createSession(
                cfg.pyqRagBackendUrl,
                AttendanceSessionRequest(
                    installationId = installationId,
                    profileFingerprint = fingerprint,
                    branch = cfg.branch,
                    subsection = cfg.studentSubsection.ifBlank { cfg.group.orEmpty() },
                    timetableGroup = cfg.group.orEmpty(),
                    displayName = cfg.studentName,
                    section = cfg.studentSection
                )
            )
            keys.setAttendanceSession(installationId, refreshed.accessToken, fingerprint)
            synchronizedFingerprint = fingerprint
            return@withLock refreshed.accessToken
        }
        val id = installationId ?: UUID.randomUUID().toString()
        val created = client.createSession(
            cfg.pyqRagBackendUrl,
            AttendanceSessionRequest(
                installationId = id,
                profileFingerprint = fingerprint,
                branch = cfg.branch,
                subsection = cfg.studentSubsection.ifBlank { cfg.group.orEmpty() },
                timetableGroup = cfg.group.orEmpty(),
                displayName = cfg.studentName,
                section = cfg.studentSection
            )
        )
        keys.setAttendanceSession(id, created.accessToken, fingerprint)
        synchronizedFingerprint = fingerprint
        created.accessToken
    }

    companion object {
        fun normalizeLectureType(value: String?): String = when (value?.trim()?.lowercase()) {
            "l", "lecture", "theory" -> "lecture"
            "p", "practical", "lab", "laboratory" -> "practical"
            "t", "tutorial" -> "tutorial"
            else -> "unspecified"
        }

        fun lectureKey(date: LocalDate, lecture: LectureEntity): String {
            val stable = listOf(
                date.toString(),
                lecture.groupName,
                lecture.startMinutes.toString(),
                lecture.endMinutes.toString(),
                lecture.subject.orEmpty(),
                lecture.teacher.orEmpty(),
                lecture.venue.orEmpty()
            ).joinToString("|")
            return sha256(stable)
        }

        private fun profileFingerprint(cfg: com.gndec.timetable.data.prefs.AppSettings): String = sha256(
            listOf(cfg.registrationNumber, cfg.rollNumber, cfg.branch, cfg.studentSubsection, cfg.studentName)
                .joinToString("|")
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
