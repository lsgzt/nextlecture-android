package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.DeptGroupSourceResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Loads the FET group catalog of a department's CURRENT official student
 * timetable (2nd/3rd/4th year onboarding + profile editing). Backed by
 * [DeptGroupSourceResolver] live discovery; caches the last good catalog per
 * branch so the picker still works offline (stale data is labeled as such and
 * never presented as current).
 */
class GroupTimetableManager(
    context: Context,
    private val resolver: DeptGroupSourceResolver = DeptGroupSourceResolver()
) {

    sealed class CatalogResult {
        data class Ready(
            val branch: String,
            val year: Int,
            val url: String,
            val groups: List<String>,
            val fromCache: Boolean
        ) : CatalogResult()

        data class Failed(val reason: String, val cached: List<String> = emptyList()) : CatalogResult()
    }

    @Serializable
    private data class CatalogFile(
        val branch: String,
        val year: Int,
        val url: String,
        val groups: List<String>,
        val fetchedAt: Long
    )

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, CatalogFile>()

    private val cacheDir: File by lazy { File(appContext.filesDir, CACHE_DIR).apply { mkdirs() } }

    suspend fun load(branch: String, year: Int, force: Boolean = false): CatalogResult =
        withContext(Dispatchers.IO) {
            val key = branch.trim().uppercase()
            // Branch codes are "CE","CS","EC","EE","IT","ME","RAI" — RAI is three
            // letters, so only reject a MISSING branch, not a longer code.
            if (key.isBlank()) return@withContext CatalogResult.Failed("Choose your branch first.")
            if (year !in 2..4) return@withContext CatalogResult.Failed("Choose your academic year first.")

            val inMemory = memory[key]
            if (!force && inMemory != null && inMemory.year == year) {
                return@withContext CatalogResult.Ready(key, year, inMemory.url, inMemory.groups, fromCache = true)
            }
            val cached = readCache(key)
            if (!force && cached != null && cached.year == year && isFresh(cached.fetchedAt)) {
                memory[key] = cached
                return@withContext CatalogResult.Ready(key, year, cached.url, cached.groups, fromCache = true)
            }

            val doc = runCatching { resolver.loadDoc(key, year) }.getOrNull()
            if (doc != null) {
                val entry = CatalogFile(key, year, doc.url, doc.groups, System.currentTimeMillis())
                writeCache(entry)
                memory[key] = entry
                return@withContext CatalogResult.Ready(key, year, doc.url, doc.groups, fromCache = false)
            }

            // Honest failure — stale cache is surfaced as a fallback, clearly labeled.
            if (cached != null && cached.year == year && cached.groups.isNotEmpty()) {
                memory[key] = cached
                return@withContext CatalogResult.Ready(key, year, cached.url, cached.groups, fromCache = true)
            }
            CatalogResult.Failed(
                "Could not load the official $key timetable. Check your internet connection and try again."
            )
        }

    private fun cacheFile(branch: String) = File(cacheDir, "$branch.json")

    private fun readCache(branch: String): CatalogFile? = runCatching {
        val f = cacheFile(branch)
        if (!f.isFile) return null
        json.decodeFromString<CatalogFile>(f.readText())
    }.getOrNull()

    private fun writeCache(entry: CatalogFile) {
        runCatching { cacheFile(entry.branch).writeText(json.encodeToString(entry)) }
    }

    private fun isFresh(fetchedAt: Long): Boolean =
        fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < CACHE_TTL_MILLIS

    companion object {
        private const val CACHE_DIR = "group_catalog"
        private const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
