package com.gndec.timetable.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gndec.timetable.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Faculty profile photos with a strict split between list thumbs and fullscreen.
 *
 * Network policy:
 * - [load] with [THUMB_MAX_DIM] downloads a **small resized JPEG** (~few KB) via
 *   an image resize endpoint, then keeps only that under [filesDir]. The college
 *   site only publishes full-size portraits; without a resize step every list
 *   scroll would pull ~100–400 KB originals.
 * - [load] with [MAX_DETAIL_DIM] downloads the **original** college URL and only
 *   when the user opens the zoomable viewer.
 *
 * Disk:
 * - Thumbs: `filesDir/faculty_photo_thumbs` (permanent, no short TTL)
 * - Full: `filesDir/faculty_photo_full` (only after a fullscreen open)
 * - Legacy `cacheDir/faculty_photos` is read once to rebuild thumbs offline
 */
class FacultyPhotoStore(
    context: Context,
    private val client: OkHttpClient = Net.client
) {
    companion object {
        /** List / avatar longest side — small enough for a circle icon. */
        const val THUMB_MAX_DIM = 128

        /** Fullscreen zoom decode budget. */
        const val MAX_DETAIL_DIM = 2048

        private const val THUMB_JPEG_QUALITY = 70

    }

    private val thumbDir = File(context.filesDir, "faculty_photo_thumbs").apply { mkdirs() }
    private val fullDir = File(context.filesDir, "faculty_photo_full").apply { mkdirs() }
    private val legacyDir = File(context.cacheDir, "faculty_photos")

    private val memory = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    suspend fun load(url: String, maxDim: Int = THUMB_MAX_DIM): Bitmap? {
        if (url.isBlank()) return null
        return if (maxDim <= THUMB_MAX_DIM) loadThumb(url) else loadFull(url, maxDim)
    }

    private suspend fun loadThumb(url: String): Bitmap? {
        val key = "$url|thumb"
        memory.get(key)?.let { return it }
        return withInFlight(key) {
            memory.get(key)?.let { return@withInFlight it }

            readThumbFile(url)?.let { bmp ->
                memory.put(key, bmp)
                return@withInFlight bmp
            }

            // Rebuild from a full original already on disk (no network).
            val fullBytes = readFullBytes(url)
            if (fullBytes != null) {
                val thumb = withContext(Dispatchers.Default) { decode(fullBytes, THUMB_MAX_DIM) }
                    ?: return@withInFlight null
                withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
                memory.put(key, thumb)
                return@withInFlight thumb
            }

            // Network: request a *resized* image, never the full portrait.
            val smallBytes = download(resizedThumbUrl(url))
            if (smallBytes != null) {
                val thumb = withContext(Dispatchers.Default) { decode(smallBytes, THUMB_MAX_DIM) }
                    ?: return@withInFlight null
                withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
                memory.put(key, thumb)
                return@withInFlight thumb
            }

            // Resize proxy unreachable — last resort only: one full download,
            // store a tiny thumb, discard the original bytes (do not write full).
            val original = download(url) ?: return@withInFlight null
            val thumb = withContext(Dispatchers.Default) { decode(original, THUMB_MAX_DIM) }
                ?: return@withInFlight null
            withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
            memory.put(key, thumb)
            thumb
        }
    }

    private suspend fun loadFull(url: String, maxDim: Int): Bitmap? {
        val key = "$url|full"
        memory.get(key)?.let { return it }
        return withInFlight(key) {
            memory.get(key)?.let { return@withInFlight it }

            val cachedBytes = readFullBytes(url)
            if (cachedBytes != null) {
                val bmp = withContext(Dispatchers.Default) { decode(cachedBytes, maxDim) }
                    ?: return@withInFlight null
                ensureThumbFromBytes(url, cachedBytes)
                memory.put(key, bmp)
                return@withInFlight bmp
            }

            // Fullscreen only: original college URL.
            val bytes = download(url) ?: return@withInFlight null
            withContext(Dispatchers.IO) { writeFullBytes(url, bytes) }
            val bmp = withContext(Dispatchers.Default) { decode(bytes, maxDim) }
                ?: return@withInFlight null
            ensureThumbFromBytes(url, bytes)
            memory.put(key, bmp)
            bmp
        }
    }

    private suspend fun ensureThumbFromBytes(url: String, bytes: ByteArray) {
        if (thumbFile(url).isFile) return
        val thumb = withContext(Dispatchers.Default) { decode(bytes, THUMB_MAX_DIM) } ?: return
        withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
        memory.put("$url|thumb", thumb)
    }

    /**
     * Public image CDN that returns a downscaled JPEG of [sourceUrl].
     * This is what makes list scrolling cheap when the origin has no thumb variant.
     */
    private fun resizedThumbUrl(sourceUrl: String): String {
        val encoded = URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8.name())
        return "https://wsrv.nl/?url=$encoded&w=$THUMB_MAX_DIM&h=$THUMB_MAX_DIM&fit=cover&q=55&output=jpg"
    }

    private suspend fun <T> withInFlight(key: String, block: suspend () -> T): T {
        val mutex = inFlightGuard.withLock {
            inFlight.getOrPut(key) { Mutex() }
        }
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
            inFlightGuard.withLock { if (inFlight[key] == mutex) inFlight.remove(key) }
        }
    }

    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "image/*,*/*")
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun thumbFile(url: String): File = File(thumbDir, sha256(url) + ".jpg")
    private fun fullFile(url: String): File = File(fullDir, sha256(url) + ".img")
    private fun legacyFile(url: String): File = File(legacyDir, sha256(url) + ".img")

    private fun readThumbFile(url: String): Bitmap? {
        val file = thumbFile(url)
        if (!file.isFile) return null
        return runCatching { decode(file.readBytes(), THUMB_MAX_DIM) }.getOrNull()
    }

    private fun writeThumbFile(url: String, bitmap: Bitmap) {
        runCatching {
            val target = thumbFile(url)
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun readFullBytes(url: String): ByteArray? {
        val primary = fullFile(url)
        if (primary.isFile) {
            return runCatching { primary.readBytes() }.getOrNull()
        }
        val legacy = legacyFile(url)
        if (legacy.isFile) {
            val bytes = runCatching { legacy.readBytes() }.getOrNull() ?: return null
            runCatching { writeFullBytes(url, bytes) }
            return bytes
        }
        return null
    }

    private fun writeFullBytes(url: String, bytes: ByteArray) {
        runCatching {
            val target = fullFile(url)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxDim) {
            sample *= 2
            longest /= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
