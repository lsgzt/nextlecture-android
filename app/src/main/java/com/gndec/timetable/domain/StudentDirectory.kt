package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.Net
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable
data class StudentDirectoryRecord(
    val srNo: String,
    val candidateName: String,
    val registrationNumber: String,
    val branch: String,
    val temporarySection: String,
    val temporarySubsection: String,
    val mentorName: String
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
        private const val BASE = "https://appsc.gndec.ac.in/sites/default/files/2026-08/"
        private val PDF_URLS = mapOf(
            "CE" to BASE + "CE%20Branch%20Temporary%20Sections%202026_0.pdf",
            "CS" to BASE + "CS%20Branch%20Temporary%20Sections%202026_0.pdf",
            "EC" to BASE + "EC%20Branch%20Temporary%20Sections%202026_0.pdf",
            "EE" to BASE + "EE%20Branch%20Temporary%20Sections%202026_0.pdf",
            "IT" to BASE + "IT%20Branch%20Temporary%20Sections%202026_0.pdf",
            "ME" to BASE + "ME%20Branch%20Temporary%20Sections%202026_0.pdf",
            "RAI" to BASE + "RAI%20Branch%20Temporary%20Sections%202026_0.pdf"
        )
        private val rowRegex = Regex("""^(\\d{1,3})\\s+(.+?)\\s+(\\d{8})\\s+([A-Z]+)\\s+([A-Z0-9]+)\\s+([A-Z0-9]+)\\s+(.+)$""")
        private val registrationRegex = Regex("(?<!\\d)\\d{8}(?!\\d)")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun load(branch: String, force: Boolean = false): StudentDirectoryResult = withContext(Dispatchers.IO) {
        val normalizedBranch = branch.trim().uppercase()
        if (normalizedBranch !in BRANCHES) return@withContext StudentDirectoryResult.Failed("Choose a supported branch first.")

        val current = settings.flow.first()
        val cached = decode(current.studentDirectoryJson)
        if (!force && current.studentDirectoryBranch == normalizedBranch && cached.isNotEmpty()) {
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, cached, fromCache = true)
        }

        val url = PDF_URLS[normalizedBranch] ?: return@withContext StudentDirectoryResult.Failed("No PDF is configured for $normalizedBranch.", cached)
        try {
            val request = Request.Builder().url(url).header("Cache-Control", "no-cache").get().build()
            Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext StudentDirectoryResult.Failed("GNDEC PDF returned HTTP ${response.code}.", cached)
                val body = response.body ?: return@withContext StudentDirectoryResult.Failed("The branch PDF was empty.", cached)
                val text = body.byteStream().use { input ->
                    PDDocument.load(input).use { document ->
                        PDFTextStripper().getText(document)
                    }
                }
                val records = parse(text, normalizedBranch)
                if (records.isEmpty()) return@withContext StudentDirectoryResult.Failed("The PDF format could not be read.", cached)
                settings.setStudentDirectoryCache(normalizedBranch, json.encodeToString(records))
                StudentDirectoryResult.Ready(normalizedBranch, records, fromCache = false)
            }
        } catch (error: Exception) {
            StudentDirectoryResult.Failed(error.message ?: "Could not download the branch PDF.", cached)
        }
    }

    suspend fun cachedFor(branch: String): List<StudentDirectoryRecord> {
        val current = settings.flow.first()
        return if (current.studentDirectoryBranch == branch.trim().uppercase()) decode(current.studentDirectoryJson) else emptyList()
    }

    private fun parse(text: String, expectedBranch: String): List<StudentDirectoryRecord> {
        return text.lineSequence()
            .map { it.replace('\u00A0', ' ').trim().replace(Regex("\\s+"), " ") }
            .mapNotNull { line ->
                rowRegex.matchEntire(line)?.let { match ->
                    StudentDirectoryRecord(
                        srNo = match.groupValues[1],
                        candidateName = match.groupValues[2].trim(),
                        registrationNumber = match.groupValues[3],
                        branch = match.groupValues[4],
                        temporarySection = match.groupValues[5],
                        temporarySubsection = match.groupValues[6],
                        mentorName = match.groupValues[7].trim()
                    )
                } ?: parseFallback(line)
            }
            .filter { it.branch == expectedBranch }
            .distinctBy { it.registrationNumber }
            .sortedBy { it.srNo.toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
    }

    private fun parseFallback(line: String): StudentDirectoryRecord? {
        val registration = registrationRegex.find(line) ?: return null
        val prefix = line.substring(0, registration.range.first).trim()
        val suffix = line.substring(registration.range.last + 1).trim().replace(Regex("\\s+"), " ")
        val tokens = suffix.split(' ')
        if (tokens.size < 4 || !prefix.matches(Regex("^\\d{1,3}\\s+.+$"))) return null
        val sr = prefix.substringBefore(' ').trim()
        val name = prefix.substringAfter(' ').trim()
        return StudentDirectoryRecord(sr, name, registration.value, tokens[0], tokens[1], tokens[2], tokens.drop(3).joinToString(" "))
    }

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
    return if (duplicate) "${record.candidateName} (${record.registrationNumber})" else record.candidateName
}
