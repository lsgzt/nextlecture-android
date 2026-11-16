package com.gndec.timetable.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Photo intake for Lost & Found. A phone camera photo (3–8 MB) never reaches
 * Supabase as-is; both stored variants are decoded, EXIF-rotated upright and
 * re-encoded here:
 *
 *  - full:  longest side <= [FULL_MAX_DIM], JPEG quality [FULL_QUALITY]
 *           (typically 80–250 KB) — opened when a post is viewed
 *  - thumb: longest side <= [THUMB_MAX_DIM], JPEG quality [THUMB_QUALITY]
 *           (typically 15–50 KB) — the only thing list scrolling downloads
 *
 * The upload size cap on the bucket (5 MB) is therefore never in play; the
 * realistic worst case stays under ~300 KB per photo.
 */
object ImageCompressor {

    const val FULL_MAX_DIM = 1280
    const val FULL_QUALITY = 72
    const val THUMB_MAX_DIM = 480
    const val THUMB_QUALITY = 60

    /** Both compressed variants of one picked photo, ready to upload. */
    data class Compressed(val full: ByteArray, val thumb: ByteArray)

    /**
     * Reads the picked image, returns the compressed pair, or null when the
     * picker handed us something undecodable. The source is copied to a temp
     * file first: EXIF and pixel data each need their own open stream.
     */
    fun compress(context: Context, uriString: String): Compressed? {
        val temp = File(context.cacheDir, "lf_pick_${UUID.randomUUID()}.img")
        return try {
            context.contentResolver.openInputStream(android.net.Uri.parse(uriString))?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: return null
            if (!temp.isFile || temp.length() == 0L) return null

            val full = decodeScaled(temp, FULL_MAX_DIM) ?: return null
            val thumb = scaleTo(full, THUMB_MAX_DIM)

            Compressed(
                full = encode(full, FULL_QUALITY),
                thumb = encode(thumb, THUMB_QUALITY)
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** Decodes a small preview for the composer without keeping a huge bitmap. */
    fun preview(context: Context, uriString: String, maxDim: Int = THUMB_MAX_DIM): Bitmap? {
        val temp = File(context.cacheDir, "lf_prev_${UUID.randomUUID()}.img")
        return try {
            context.contentResolver.openInputStream(android.net.Uri.parse(uriString))?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: return null
            decodeScaled(temp, maxDim)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** Decode with inSampleSize, then the exact EXIF orientation. */
    private fun decodeScaled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
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
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val upright = rotateForExif(file, decoded)
        return scaleTo(upright, maxDim)
    }

    /**
     * Camera photos are often stored landscape with an EXIF rotation tag.
     * Applying the tag here keeps every posted photo upright in every viewer,
     * including the in-app one, which decodes the re-encoded (tag-stripped)
     * JPEG with no orientation handling at all.
     */
    private fun rotateForExif(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).rotationDegrees
        }.getOrDefault(0)
        if (orientation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** Exact final resize so both variants hit their size budget precisely. */
    private fun scaleTo(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        return bytes.toByteArray()
    }
}
