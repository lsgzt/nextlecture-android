package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class PyqFrequencyGroup(
    @SerialName("group_id") val groupId: Long,
    @SerialName("representative_title") val title: String,
    @SerialName("representative_description") val description: String? = null,
    val frequency: Long = 0,
    val confidence: Double? = null
)

@Serializable
data class PyqFrequentlyAskedResponse(
    val course: String,
    val from: Int? = null,
    val to: Int? = null,
    val groups: List<PyqFrequencyGroup> = emptyList(),
    val servedFromCache: Boolean = false
)

@Serializable
data class PyqSourcePaper(
    val id: String,
    val title: String,
    @SerialName("course_code") val courseCode: String? = null,
    val year: Int? = null,
    @SerialName("exam_session") val examSession: String? = null,
    @SerialName("drive_url") val driveUrl: String
)

@Serializable
data class PyqGroupQuestion(
    val id: Long,
    @SerialName("paper_id") val paperId: String,
    @SerialName("question_number") val questionNumber: String,
    @SerialName("question_text") val questionText: String,
    @SerialName("source_page") val sourcePage: Int,
    @SerialName("extraction_method") val extractionMethod: String? = null,
    val paper: PyqSourcePaper? = null
)

@Serializable
data class PyqGroupOccurrence(
    @SerialName("similarity_score") val similarityScore: Double = 0.0,
    val question: PyqGroupQuestion? = null
)

@Serializable
data class PyqGroupDetailResponse(
    val group: PyqFrequencyGroup,
    val frequency: Int,
    val occurrences: List<PyqGroupOccurrence> = emptyList()
)

class PyqRagClient(private val client: OkHttpClient = Net.client) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun frequentlyAsked(baseUrl: String, course: String, from: Int? = null, to: Int? = null): PyqFrequentlyAskedResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/pyq/frequently-asked")?.newBuilder()
            ?.addQueryParameter("course", course.trim().uppercase())
            ?.apply { from?.let { addQueryParameter("from", it.toString()) }; to?.let { addQueryParameter("to", it.toString()) } }
            ?.build() ?: throw IllegalArgumentException("Invalid PYQ RAG backend URL")
        execute(url, PyqFrequentlyAskedResponse.serializer())
    }

    suspend fun groupDetails(baseUrl: String, groupId: Long): PyqGroupDetailResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(baseUrl, "api/pyq/frequently-asked/$groupId") ?: throw IllegalArgumentException("Invalid PYQ RAG backend URL")
        execute(url, PyqGroupDetailResponse.serializer())
    }

    private fun buildUrl(baseUrl: String, path: String) = baseUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path)?.build()

    private fun <T> execute(url: okhttp3.HttpUrl, serializer: kotlinx.serialization.KSerializer<T>): T {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "PYQ RAG request failed")
            val body = response.body?.string() ?: throw HttpException(response.code, "empty PYQ RAG response")
            return json.decodeFromString(serializer, body)
        }
    }
}
