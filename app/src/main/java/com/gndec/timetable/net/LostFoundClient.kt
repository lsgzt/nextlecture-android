package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One Lost & Found post, as stored in the Supabase `lost_found` table. */
@Serializable
data class LostFoundItem(
    val id: String,
    val kind: String,
    val title: String,
    val description: String = "",
    val image_path: String? = null,
    val created_at: String,
    /**
     * How other students reach the poster: phone number, email or handle.
     * Blank on the few demo rows seeded before the field existed.
     */
    val contact_info: String = ""
) {
    companion object {
        const val KIND_LOST = "lost"
        const val KIND_FOUND = "found"
    }
}

/**
 * Thin Supabase REST client (PostgREST + Storage) for the campus Lost & Found.
 *
 * Everything runs with the project's PUBLISHABLE key only — the equivalent
 * of the legacy anon key. Row Level Security on `public.lost_found` allows
 * public reads; every write goes through two SECURITY DEFINER functions:
 * `create_lost_found_post` (validates the post, stores the contact info and
 * the hash of a poster-chosen delete PIN) and `delete_lost_found_post`
 * (deletes the row only for the matching PIN). No secret key ever ships
 * inside the app.
 *
 * Bandwidth policy: every photo is uploaded twice — a full image
 * (<=1280px, JPEG q72) and a `_t` thumbnail (<=480px, JPEG q60). List
 * scrolling only ever downloads the thumbnail, which is typically 20–30%
 * the size of the full image; the full image is fetched when the student
 * opens a post.
 */
class LostFoundClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** New posts, newest first, optionally only one tab. */
    suspend fun list(kind: String? = null, limit: Int = LIST_LIMIT): List<LostFoundItem> =
        withContext(Dispatchers.IO) {
            val filter = if (kind == null) "" else "&kind=eq.$kind"
            val request = builder("$REST_URL?select=*&order=created_at.desc&limit=$limit$filter")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "Lost & Found feed unavailable")
                }
                val body = response.body?.string().orEmpty()
                json.decodeFromString<List<LostFoundItem>>(body)
            }
        }

    /**
     * Inserts one post through the `create_lost_found_post` RPC, which also
     * stores the SHA-256 hash of [pin] in a table no public role can read.
     * [imagePath] must already have been uploaded via [uploadImage]. Returns
     * the created row.
     */
    suspend fun insert(
        kind: String,
        title: String,
        description: String,
        contact: String,
        pin: String,
        imagePath: String?
    ): LostFoundItem = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("p_kind", kind)
            put("p_title", title)
            put("p_description", description)
            put("p_contact", contact)
            put("p_pin", pin)
            if (imagePath != null) put("p_image_path", imagePath) else put("p_image_path", JsonNull)
        }
        val request = builder(CREATE_RPC_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpException(response.code, friendlyInsertError(response.code, body))
            }
            json.decodeFromString<LostFoundItem>(body)
        }
    }

    /**
     * Deletes one post through the `delete_lost_found_post` RPC — it only
     * succeeds when [pin] matches the PIN the poster chose at creation time
     * ("item collected", "founder returned it"). On success the row and its
     * PIN hash are gone; the post's two image objects become unreferenced
     * orphans, and the Storage API delete that follows is allowed by the
     * orphan-only policy for exactly that state. Image cleanup is
     * best-effort: a failure never resurrects the post.
     */
    suspend fun deletePost(id: String, pin: String, imagePath: String?): Boolean =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("p_id", id)
                put("p_pin", pin)
            }
            val request = builder(DELETE_RPC_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val ok = client.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.trim().equals("true", ignoreCase = true)
            }
            if (ok && imagePath != null) {
                deleteStorageObject(imagePath)
                deleteStorageObject(thumbPath(imagePath))
            }
            ok
        }

    /** Uploads one JPEG under [path] inside the public `lostfound` bucket. */
    suspend fun uploadImage(path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val request = builder("$STORAGE_OBJECT_URL/$path")
            .header("Content-Type", "image/jpeg")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(JPEG_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw HttpException(response.code, "Image upload failed: ${body.take(160)}")
            }
        }
    }

    /** Removes one object from the bucket; failures are swallowed on purpose. */
    private suspend fun deleteStorageObject(path: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val request = builder("$STORAGE_OBJECT_URL/$path")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                response.body?.close()
            }
        }
    }

    private fun builder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $PUBLISHABLE_KEY")

    /**
     * PostgREST renders a raised P0001 as 500 with the message in the body;
     * surface that text (it is written for students), falling back to the
     * generic transport errors.
     */
    private fun friendlyInsertError(code: Int, body: String): String {
        val serverMessage = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)
                ?.get("message")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return when {
            serverMessage != null -> serverMessage
            code == 413 -> "The post is too large"
            else -> "Could not post ($code)"
        }
    }

    companion object {
        const val PROJECT_URL = "https://kitemjxgqisxpefsqjlc.supabase.co"
        const val BUCKET = "lostfound"

        /**
         * Publishable API key — the public client key of this Supabase project,
         * safe to embed in the app exactly like a Firebase web config. It grants
         * only what the two Lost & Found RPCs and the storage policies grant.
         */
        const val PUBLISHABLE_KEY = "sb_publishable_JcLDNq6fDbrYRhc5SMwYcg_P-HariqH"

        private const val REST_URL = "$PROJECT_URL/rest/v1/lost_found"
        private const val CREATE_RPC_URL = "$PROJECT_URL/rest/v1/rpc/create_lost_found_post"
        private const val DELETE_RPC_URL = "$PROJECT_URL/rest/v1/rpc/delete_lost_found_post"
        private const val STORAGE_OBJECT_URL = "$PROJECT_URL/storage/v1/object/$BUCKET"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA = "image/jpeg".toMediaType()

        /** Newest ~100 entries is plenty for a campus noticeboard. */
        const val LIST_LIMIT = 100

        /** Download URL of a stored image (public bucket, no auth headers). */
        fun imageUrl(path: String): String = "$PROJECT_URL/storage/v1/object/public/$BUCKET/$path"

        /**
         * Path of the small variant of a stored image: 'found/x.jpg' ->
         * 'found/x_t.jpg'. Both are uploaded together, so the derivation
         * only ever formats a name the uploader itself produced.
         */
        fun thumbPath(path: String): String {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return "$dir/${name}_t"
            return "$dir/${name.substring(0, dot)}_t${name.substring(dot)}"
        }

        /** Download URL of the small (list) variant of a stored image. */
        fun thumbUrl(path: String): String = imageUrl(thumbPath(path))

        /**
         * A poster-chosen delete PIN: 4–8 digits, digits only. Short enough
         * to remember, long enough that guessing it at the delete dialog is
         * not practical.
         */
        fun isValidDeletePin(pin: String): Boolean =
            pin.length in 4..8 && pin.all { it.isDigit() }

        /**
         * One line of contact info: collapses runs of whitespace so pasted
         * "98765  43210" becomes "98765 43210", trims edges, caps the length
         * at the server's 120-char limit.
         */
        fun sanitizeContact(raw: String): String =
            raw.trim().replace(Regex("\\s+"), " ").take(120)
    }
}
