package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.R
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.net.Net
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request

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
    private val settings: SettingsManager,
    private val keys: SecureKeyStore
) {
    companion object {
        val BRANCHES = listOf("CE", "CS", "EC", "EE", "IT", "ME", "RAI")

        // The bundled PDFs/JSON remain the official offline fallback. Live data is fetched
        // from the college website so section changes published mid-semester appear without
        // waiting for an app update. Registration numbers are additionally stored inside
        // the app (bundled directory) and used only when a fetched row lacks one.
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

        // Official permanent-section documents per branch (appsc.gndec.ac.in, Aug 2026 upload).
        private val OFFICIAL_PDF_URLS = mapOf(
            "CE" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/CE%20Permanent%20Sections%202026_0.pdf",
            "CS" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/CS%20Permanent%20Sections%202026_0.pdf",
            "EC" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/EC%20Permanent%20Sections%202026_1.pdf",
            "EE" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/EE%20Permanent%20Sections%202026_0.pdf",
            "IT" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/IT%20Permanent%20Sections%202026_0.pdf",
            "ME" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/ME%20Permanent%20Sections%202026_0.pdf",
            "RAI" to "https://appsc.gndec.ac.in/sites/default/files/2026-08/RAI%20Permanent%20Sections%202026_1.pdf"
        )

        private const val CACHE_DIR = "student_directory"
        private const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L  // 6 hours
        private const val FETCH_TIMEOUT_MILLIS = 30_000L
    }

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memoryCache = ConcurrentHashMap<String, List<StudentDirectoryRecord>>()
    private val bundledDirectory: List<StudentDirectoryRecord> by lazy { readBundledDirectory() }

    init {
        runCatching { PDFBoxResourceLoader.init(appContext) }
    }

    private val cacheDir: File by lazy { File(appContext.filesDir, CACHE_DIR).apply { mkdirs() } }

    /** Reads pre-parsed records bundled in the APK; used offline and as the name-split source. */
    suspend fun load(branch: String, force: Boolean = false): StudentDirectoryResult = withContext(Dispatchers.IO) {
        val normalizedBranch = branch.trim().uppercase()
        if (normalizedBranch !in BRANCHES) return@withContext StudentDirectoryResult.Failed("Choose a supported branch first.")
        if (OFFICIAL_PDF_URLS[normalizedBranch] == null || PERMANENT_PDF_RESOURCES[normalizedBranch] == null) {
            return@withContext StudentDirectoryResult.Failed("No student list is configured for $normalizedBranch.")
        }

        val inMemory = memoryCache[normalizedBranch]
        if (!force && inMemory != null) {
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, inMemory, fromCache = true)
        }

        val cached = readCacheFile(normalizedBranch)
        if (!force && cached != null && isFresh(cached.fetchedAt)) {
            memoryCache[normalizedBranch] = cached.records
            settings.setStudentDirectoryCache(normalizedBranch, json.encodeToString(cached.records), cached.fetchedAt)
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, cached.records, fromCache = true)
        }

        // Live fetch from the college website; never trusted without validation.
        val fetched = runCatching { fetchFromWeb(normalizedBranch) }.getOrNull()
        if (fetched != null && isValidFetch(normalizedBranch, fetched)) {
            writeCacheFile(normalizedBranch, fetched)
            memoryCache[normalizedBranch] = fetched
            settings.setStudentDirectoryCache(normalizedBranch, json.encodeToString(fetched))
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, fetched, fromCache = false)
        }

        // Fallback chain: stale fetched cache, then the bundled directory.
        if (cached != null && cached.records.isNotEmpty()) {
            memoryCache[normalizedBranch] = cached.records
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, cached.records, fromCache = true)
        }
        val bundled = bundledDirectory.filter { it.branch == normalizedBranch }.sortedBy { it.crn }
        if (bundled.isNotEmpty()) {
            memoryCache[normalizedBranch] = bundled
            settings.setStudentDirectoryCache(normalizedBranch, json.encodeToString(bundled))
            return@withContext StudentDirectoryResult.Ready(normalizedBranch, bundled, fromCache = false)
        }
        StudentDirectoryResult.Failed("The student list could not be loaded. Check your internet connection and try again.")
    }

    suspend fun cachedFor(branch: String): List<StudentDirectoryRecord> {
        val normalizedBranch = branch.trim().uppercase()
        memoryCache[normalizedBranch]?.let { return it }
        readCacheFile(normalizedBranch)?.let { return it.records }
        val current = settings.flow.first()
        return if (current.studentDirectoryBranch == normalizedBranch) decode(current.studentDirectoryJson) else emptyList()
    }

    /**
     * Background refresh for the saved profile's branch (periodic worker). Silent: any
     * failure keeps the previously fetched or bundled data untouched.
     */
    suspend fun refreshSavedBranch() {
        val current = settings.flow.first()
        val branch = current.branch.trim().uppercase()
        if (branch !in BRANCHES) return
        // Background refresh only matters for the first-year directory workflow.
        if (current.academicYear >= 2) return
        val cached = readCacheFile(branch)
        if (cached != null && isFresh(cached.fetchedAt)) return
        runCatching { load(branch, force = false) }
    }

    /**
     * Re-links a previously saved profile to the latest official directory data.
     * Identity is resolved by CRN first (the college's unique roll number), so two
     * students sharing a name can never be mixed; the historical name-based cascade
     * remains only as a fallback for older saved profiles.
     */
    suspend fun migrateSavedProfileIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val current = settings.flow.first()
        if (current.studentName.isBlank() || current.branch !in BRANCHES || current.profileSource == "manual") return@withContext false
        // The directory PDFs describe FIRST-YEAR permanent sections only — a
        // 2nd/3rd/4th-year profile must never be re-linked against them.
        if (current.academicYear >= 2) return@withContext false

        val directory = when (val result = load(current.branch, force = false)) {
            is StudentDirectoryResult.Ready -> result.records
            is StudentDirectoryResult.Failed -> return@withContext false
        }

        val savedCrn = current.rollNumber.trim()
        val crnMatch = directory.firstOrNull { it.crn == savedCrn && savedCrn.isNotBlank() }
        val match = crnMatch ?: run {
            val savedName = normalizeStudentName(current.studentName)
            if (savedName.isBlank()) return@run null
            val nameCandidates = directory.filter { candidate ->
                val fullName = normalizeStudentName(candidate.candidateName)
                fullName == savedName || fullName.startsWith("$savedName ")
            }
            val groupCandidates = nameCandidates.filter { it.group.equals(current.studentGroup, ignoreCase = true) }
            val subsectionCandidates = nameCandidates.filter { it.subsection.equals(current.studentSubsection, ignoreCase = true) }
            val sectionCandidates = nameCandidates.filter { it.section.equals(current.studentSection, ignoreCase = true) }
            when {
                groupCandidates.size == 1 -> groupCandidates.single()
                subsectionCandidates.size == 1 -> subsectionCandidates.single()
                sectionCandidates.size == 1 -> sectionCandidates.single()
                nameCandidates.size == 1 -> nameCandidates.single()
                else -> null
            }
        } ?: return@withContext false

        val needsUpgrade = current.studentName != match.candidateName ||
            current.rollNumber != match.crn ||
            current.registrationNumber == match.crn ||
            current.registrationNumber != match.registrationNumber ||
            current.fatherName.isBlank() ||
            current.motherName.isBlank() ||
            current.group != match.subsection
        if (!needsUpgrade) return@withContext false

        keys.removeAttendanceSession()
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

    private suspend fun fetchFromWeb(branch: String): List<StudentDirectoryRecord> = withContext(Dispatchers.IO) {
        withTimeout(FETCH_TIMEOUT_MILLIS) {
            val url = OFFICIAL_PDF_URLS.getValue(branch)
            val request = Request.Builder().url(url).get().build()
            val bytes = Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} while fetching $branch directory")
                response.body?.bytes() ?: throw IllegalStateException("Empty directory download for $branch")
            }
            if (bytes.size < 1_000L) throw IllegalStateException("Directory download for $branch is too small to be a section PDF")
            val text = PDDocument.load(bytes).use { document ->
                PDFTextStripper().getText(document)
            }
            StudentDirectoryParser.parse(
                lines = text.lines(),
                branch = branch,
                nameSplits = bundledNameSplits(),
                registrationFallback = bundledRegistrationMap()
            )
        }
    }

    /**
     * Validation gate before fetched data may replace cached data: rows must carry a
     * usable identity (CRN + name + section), CRNs must be unique, and the row count
     * must stay plausible versus the bundled list (guards against parsing the wrong
     * document). A failed gate simply keeps the previous data.
     */
    private fun isValidFetch(branch: String, records: List<StudentDirectoryRecord>): Boolean {
        if (records.isEmpty()) return false
        if (records.any {
                it.crn.length != 7 || it.crn.any { c -> !c.isDigit() } ||
                    it.candidateName.isBlank() || it.section.isBlank() || it.subsection.isBlank()
            }) return false
        if (records.map { it.crn }.distinct().size != records.size) return false
        val bundledCount = bundledDirectory.count { it.branch == branch }
        if (bundledCount > 0 && records.size * 2 < bundledCount) return false
        return true
    }

    private fun bundledNameSplits(): Map<String, StudentDirectoryParser.NameSplit> =
        bundledDirectory.associate {
            it.crn to StudentDirectoryParser.NameSplit(it.candidateName, it.fatherName, it.motherName)
        }

    private fun bundledRegistrationMap(): Map<String, String> =
        bundledDirectory.mapNotNull { record ->
            record.registrationNumber.takeIf { it.isNotBlank() }?.let { record.crn to it }
        }.toMap()

    private data class DirectoryCacheFile(val fetchedAt: Long, val records: List<StudentDirectoryRecord>)

    private fun cacheFile(branch: String): File = File(cacheDir, "$branch.json")

    private fun readCacheFile(branch: String): DirectoryCacheFile? = runCatching {
        val file = cacheFile(branch)
        if (!file.isFile) return null
        json.decodeFromString<DirectoryCacheFile>(file.readText())
    }.getOrNull()

    private fun writeCacheFile(branch: String, records: List<StudentDirectoryRecord>) {
        runCatching {
            cacheFile(branch).writeText(json.encodeToString(DirectoryCacheFile(System.currentTimeMillis(), records)))
        }
    }

    private fun isFresh(fetchedAt: Long): Boolean =
        fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < CACHE_TTL_MILLIS

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
