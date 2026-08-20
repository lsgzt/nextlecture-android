package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.R
import com.gndec.timetable.data.prefs.SettingsManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StudentDirectoryRecord(
    val crn: String = "",
    val registrationNumber: String = "",
    val candidateName: String = "",
    val fatherName: String = "",
    val motherName: String = "",
    val branch: String = "",
    val section: String = "",
    val subsection: String = "",
    val group: String = "",
    val mentorName: String = "",
    val mentorMobile: String = "",
    val venue: String = ""
)

sealed class StudentDirectoryResult {
    data class Ready(val branch: String, val records: List<StudentDirectoryRecord>, val fromCache: Boolean) : StudentDirectoryResult()
    data class Failed(val reason: String, val cached: List<StudentDirectoryRecord> = emptyList()) : StudentDirectoryResult()
}

class StudentDirectoryManager(
    context: Context,
    private val settings: SettingsManager
) {
    companion object {
        val BRANCHES = listOf("CE", "CS", "EC", "EE", "IT", "ME", "RAI")
        // The PDFs remain bundled as the official offline source documents. The generated
        // JSON below is derived from those PDFs plus the bundled temporary-registration map.
        private val PERMANENT_PDF_RESOURCES = mapOf(
            "CE" to R.raw.ce_permanent_sections_2026,
            "CS" to R.raw.cs_permanent_sections_2026,
            "EC" to R.raw.ec_permanent_sections_2026,
            "EE" to R.raw.ee_permanent_sections_2026,
            "IT" to R.raw.it_permanent_sections_2026,
            "ME" to R.raw.me_permanent_sections_2026,
            "RAI" to R.raw.rai_permanent_sections_2026
        )
        private val DIRECTORY_RESOURCE = R.raw.student_directory_permanent_2026
    }

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memoryCache = ConcurrentHashMap<String, List<StudentDirectoryRecord>>()
    private val bundledDirectory: List<StudentDirectoryRecord> by lazy { readBundledDirectory() }

    /** Reads pre-parsed records bundled in the APK; no student-data network request is made. */
    suspend fun load(branch: String, force: Boolean = false): StudentDirectoryResult = withContext(Dispatchers.IO) {
        val normalizedBranch = branch.trim().uppercase()
        if (normalizedBranch !in BRANCHES) return@withContext StudentDirectoryResult.Failed("Choose a supported branch first.")

        val inMemory = memoryCache[normalizedBranch]
        if (!force && inMemory != null) {
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, inMemory, fromCache = true)
        }

        // Touch the resource map so every supplied branch PDF remains an explicit part of
        // the offline directory contract even though lookup uses its pre-parsed companion.
        if (PERMANENT_PDF_RESOURCES[normalizedBranch] == null) {
            return@withContext StudentDirectoryResult.Failed("No bundled permanent PDF is configured for $normalizedBranch.")
        }
        val records = bundledDirectory.filter { it.branch == normalizedBranch }
        if (records.isEmpty()) {
            return@withContext StudentDirectoryResult.Failed("The bundled permanent student list could not be read.")
        }
        memoryCache[normalizedBranch] = records
        settings.setStudentDirectoryCache(normalizedBranch, json.encodeToString(records))
        StudentDirectoryResult.Ready(normalizedBranch, records, fromCache = false)
    }

    suspend fun cachedFor(branch: String): List<StudentDirectoryRecord> {
        val normalizedBranch = branch.trim().uppercase()
        memoryCache[normalizedBranch]?.let { return it }
        val current = settings.flow.first()
        return if (current.studentDirectoryBranch == normalizedBranch) decode(current.studentDirectoryJson) else emptyList()
    }

    /** Upgrades profiles saved by older builds without changing manually entered profiles. */
    suspend fun migrateSavedProfileIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val current = settings.flow.first()
        if (current.studentName.isBlank() || current.branch !in BRANCHES || current.profileSource == "manual") return@withContext false

        val directory = when (val result = load(current.branch, force = false)) {
            is StudentDirectoryResult.Ready -> result.records
            is StudentDirectoryResult.Failed -> return@withContext false
        }
        val savedName = normalizeStudentName(current.studentName)
        if (savedName.isBlank()) return@withContext false

        val nameCandidates = directory.filter { candidate ->
            val fullName = normalizeStudentName(candidate.candidateName)
            fullName == savedName || fullName.startsWith("$savedName ")
        }
        val groupCandidates = nameCandidates.filter { it.group.equals(current.studentGroup, ignoreCase = true) }
        val subsectionCandidates = nameCandidates.filter { it.subsection.equals(current.studentSubsection, ignoreCase = true) }
        val sectionCandidates = nameCandidates.filter { it.section.equals(current.studentSection, ignoreCase = true) }
        val match = when {
            groupCandidates.size == 1 -> groupCandidates.single()
            subsectionCandidates.size == 1 -> subsectionCandidates.single()
            sectionCandidates.size == 1 -> sectionCandidates.single()
            nameCandidates.size == 1 -> nameCandidates.single()
            else -> null
        } ?: return@withContext false

        val needsUpgrade = current.studentName != match.candidateName ||
            current.rollNumber != match.crn ||
            current.registrationNumber == match.crn ||
            current.fatherName.isBlank() ||
            current.motherName.isBlank() ||
            current.group != match.subsection
        if (!needsUpgrade) return@withContext false

        settings.saveStudentProfile(
            name = match.candidateName,
            rollNumber = match.crn,
            branch = match.branch,
            registrationNumber = match.registrationNumber,
            fatherName = match.fatherName,
            motherName = match.motherName,
            mentorName = match.mentorName,
            section = match.section,
            subsection = match.subsection,
            studentGroup = match.group,
            mentorMobile = match.mentorMobile,
            mentorVenue = match.venue,
            source = "gndec_permanent_pdf"
        )
        settings.setGroup(match.subsection)
        true
    }

    private fun readBundledDirectory(): List<StudentDirectoryRecord> = runCatching {
        appContext.resources.openRawResource(DIRECTORY_RESOURCE).bufferedReader().use { reader ->
            json.decodeFromString<List<StudentDirectoryRecord>>(reader.readText())
        }
    }.getOrDefault(emptyList())

    private fun decode(value: String): List<StudentDirectoryRecord> = runCatching {
        if (value.isBlank()) emptyList() else json.decodeFromString<List<StudentDirectoryRecord>>(value)
    }.getOrDefault(emptyList())
}

fun normalizeStudentName(value: String): String = value.trim().uppercase().replace(Regex("\\s+"), " ")

fun matchingStudents(records: List<StudentDirectoryRecord>, query: String): List<StudentDirectoryRecord> {
    val needle = normalizeStudentName(query)
    if (needle.length < 2) return emptyList()
    return records.filter { normalizeStudentName(it.candidateName).contains(needle) }.take(30)
}

fun studentDisplayName(record: StudentDirectoryRecord, matches: List<StudentDirectoryRecord>): String {
    val duplicate = matches.count { normalizeStudentName(it.candidateName) == normalizeStudentName(record.candidateName) } > 1
    return if (duplicate) "${record.candidateName} (${record.crn})" else record.candidateName
}
