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

    suspend fun load(from: LocalDate, to: LocalDate, target: Double): AttendanceResponse {
        val cfg = settings.flow.first()
        return withSession(cfg) { token ->
            client.list(cfg.pyqRagBackendUrl, token, from.toString(), to.toString(), target)
        }
    }

    suspend fun mark(date: LocalDate, lecture: LectureEntity, status: String): AttendanceRecord {
        require(status == "present" || status == "absent")
        val cfg = settings.flow.first()
        return withSession(cfg) { token ->
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
                    endMinutes = lecture.endMinutes
                )
            )
        }
    }

    suspend fun unmark(date: LocalDate, lecture: LectureEntity) {
        val cfg = settings.flow.first()
        withSession(cfg) { token ->
            client.remove(cfg.pyqRagBackendUrl, token, date.toString(), lectureKey(date, lecture))
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
            operation(ensureSession(cfg))
        }
    }

    private suspend fun ensureSession(cfg: com.gndec.timetable.data.prefs.AppSettings): String = sessionMutex.withLock {
        val fingerprint = profileFingerprint(cfg)
        val installationId = keys.getAttendanceInstallationId()
        val savedToken = keys.getAttendanceToken()
        if (!installationId.isNullOrBlank() && !savedToken.isNullOrBlank() && keys.getAttendanceProfileFingerprint() == fingerprint) {
            return@withLock savedToken
        }
        val id = installationId ?: UUID.randomUUID().toString()
        val created = client.createSession(
            cfg.pyqRagBackendUrl,
            AttendanceSessionRequest(
                installationId = id,
                profileFingerprint = fingerprint,
                branch = cfg.branch,
                subsection = cfg.studentSubsection.ifBlank { cfg.group.orEmpty() },
                timetableGroup = cfg.group.orEmpty()
            )
        )
        keys.setAttendanceSession(id, created.accessToken, fingerprint)
        created.accessToken
    }

    companion object {
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
