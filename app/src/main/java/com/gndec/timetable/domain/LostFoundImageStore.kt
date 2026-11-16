package com.gndec.timetable.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gndec.timetable.net.LostFoundClient
import com.gndec.timetable.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bitmaps for Lost & Found posts, with the same thrift as faculty photos:
 *
 * - List scrolling only ever downloads the `_t` thumbnail — the small
 *   variant the uploader produced — never the full photo.
 * - Everything is LRU-cached in memory and on disk, so a re-opened screen
 *   costs zero bytes and the feed survives a flaky connection.
 * - The full image is downloaded exactly once, when a post is opened.
 */
class LostFoundImageStore(
    context: Context,
    private val client: OkHttpClient = Net.client
) {
    companion object {
        /** Longest side of a decoded thumbnail. */
        const val THUMB_DECODE_DIM = 480

        /** Longest side of a decoded full photo. */
        const val FULL_DECODE_DIM = 1280

        private const val THUMB_DIR = "lostfound_thumbs"
        private const val FULL_DIR = "lostfound_full"
    }

    private val thumbDir = File(context.filesDir, THUMB_DIR).apply { mkdirs() }
    private val fullDir = File(context.filesDir, FULL_DIR).apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(96) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    /** Thumbnail bitmap for a post's image path; null when it can't be loaded. */
    suspend fun loadThumb(imagePath: String): Bitmap? =
        load(imagePath, thumb = true)

    /** Full-resolution bitmap for a post's image path; null when it can't be loaded. */
    suspend fun loadFull(imagePath: String): Bitmap? =
        load(imagePath, thumb = false)

    private suspend fun load(imagePath: String, thumb: Boolean): Bitmap? {
        if (imagePath.isBlank()) return null
        val url = if (thumb) LostFoundClient.thumbUrl(imagePath) else LostFoundClient.imageUrl(imagePath)
        val key = "$url|${if (thumb) "t" else "f"}"
        memory.get(key)?.let { return it }
        return withInFlight(key) {
            memory.get(key)?.let { return@withInFlight it }

            val diskFile = if (thumb) thumbFile(imagePath) else fullFile(imagePath)
            val fromDisk = withContext(Dispatchers.IO) {
                if (!diskFile.isFile) null
                else runCatching { decode(diskFile.readBytes(), if (thumb) THUMB_DECODE_DIM else FULL_DECODE_DIM) }.getOrNull()
            }
            if (fromDisk != null) {
                memory.put(key, fromDisk)
                return@withInFlight fromDisk
            }

            val bytes = download(url) ?: return@withInFlight null
            val decoded = withContext(Dispatchers.Default) { decode(bytes, if (thumb) THUMB_DECODE_DIM else FULL_DECODE_DIM) }
                ?: return@withInFlight null
            withContext(Dispatchers.IO) { writeDisk(diskFile, bytes) }
            memory.put(key, decoded)
            decoded
        }
    }

    private suspend fun <T> withInFlight(key: String, block: suspend () -> T): T {
        val mutex = inFlightGuard.withLock { inFlight.getOrPut(key) { Mutex() } }
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

    private fun thumbFile(imagePath: String) = File(thumbDir, sha256(imagePath) + ".jpg")
    private fun fullFile(imagePath: String) = File(fullDir, sha256(imagePath) + ".img")

    private fun writeDisk(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
