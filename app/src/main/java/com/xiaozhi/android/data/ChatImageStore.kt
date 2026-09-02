package com.xiaozhi.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.xiaozhi.android.core.ImageCodecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

data class StoredChatImage(
    val uploadBytes: ByteArray,
    val fullPath: String,
    val thumbnailPath: String
)

object ChatImageStore {
    suspend fun read(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                bytes.takeIf { it.size <= MAX_SOURCE_BYTES }
            }
        }.getOrNull()
    }

    suspend fun store(
        context: Context,
        sourceBytes: ByteArray
    ): StoredChatImage? = withContext(Dispatchers.IO) {
        val uploadSourceBytes = if (ImageCodecs.looksLikeJpeg(sourceBytes)) {
            sourceBytes
        } else {
            transcodeToJpeg(sourceBytes)
        }
        if (uploadSourceBytes == null) {
            Log.w(
                TAG,
                "Chat image decode/transcode failed, bytes=${sourceBytes.size}"
            )
            return@withContext null
        }

        val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        if (!directory.isDirectory) {
            Log.w(TAG, "Chat image directory unavailable: ${directory.absolutePath}")
            return@withContext null
        }

        val id = UUID.randomUUID().toString()
        val sourceFile = File(directory, "${id}_source.jpg")
        if (!writeBytes(sourceFile, uploadSourceBytes)) {
            Log.w(
                TAG,
                "Chat source image write failed, bytes=${uploadSourceBytes.size}"
            )
            return@withContext null
        }

        var fullFile = sourceFile
        var uploadBytes = uploadSourceBytes
        var thumbnailFile = sourceFile
        val bitmap = runCatching { decodeOrientedBitmap(uploadSourceBytes) }.getOrNull()
        if (bitmap == null) {
            Log.w(
                TAG,
                "Chat image decode failed; keeping source, bytes=${sourceBytes.size}"
            )
            return@withContext StoredChatImage(
                uploadBytes = uploadBytes,
                fullPath = fullFile.absolutePath,
                thumbnailPath = thumbnailFile.absolutePath
            )
        }

        try {
            val fullBitmap = scaleBitmap(bitmap, MAX_FULL_DIMENSION)
            val scaledFullFile = File(directory, "$id.jpg")
            try {
                if (writeBitmap(scaledFullFile, fullBitmap, FULL_QUALITY)) {
                    fullFile = scaledFullFile
                    uploadBytes = scaledFullFile.readBytes()
                    sourceFile.delete()
                } else {
                    Log.w(TAG, "Chat full image compression failed; using source")
                    scaledFullFile.delete()
                }
            } finally {
                recycleDerivedBitmap(fullBitmap, bitmap)
            }

            val thumbnailBitmap = scaleBitmap(bitmap, MAX_THUMBNAIL_DIMENSION)
            val scaledThumbnailFile = File(directory, "${id}_thumb.jpg")
            try {
                if (writeBitmap(scaledThumbnailFile, thumbnailBitmap, THUMBNAIL_QUALITY)) {
                    thumbnailFile = scaledThumbnailFile
                } else {
                    Log.w(TAG, "Chat thumbnail compression failed; using full image")
                    scaledThumbnailFile.delete()
                }
            } finally {
                recycleDerivedBitmap(thumbnailBitmap, bitmap)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Chat image processing failed; using source", error)
        } finally {
            recycleBitmap(bitmap)
        }

        StoredChatImage(
            uploadBytes = uploadBytes,
            fullPath = fullFile.absolutePath,
            thumbnailPath = thumbnailFile.absolutePath
        )
    }

    private fun writeBytes(file: File, bytes: ByteArray): Boolean {
        return runCatching {
            file.outputStream().use { output -> output.write(bytes) }
        }.isSuccess
    }

    private fun writeBitmap(
        file: File,
        bitmap: Bitmap,
        quality: Int
    ): Boolean {
        return runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
        }.getOrDefault(false)
    }

    private fun transcodeToJpeg(sourceBytes: ByteArray): ByteArray? {
        val bitmap = runCatching { decodeOrientedBitmap(sourceBytes) }.getOrNull()
            ?: return null
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, FULL_QUALITY, output)) {
                return null
            }
            return output.toByteArray().takeIf {
                it.size >= 4 && ImageCodecs.looksLikeJpeg(it)
            }
        } finally {
            recycleBitmap(bitmap)
        }
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun recycleDerivedBitmap(bitmap: Bitmap, source: Bitmap) {
        if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun decodeOrientedBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= MAX_FULL_DIMENSION &&
            bounds.outHeight / (sampleSize * 2) >= MAX_FULL_DIMENSION
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded

        val rotated = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            Matrix().apply { postRotate(degrees) },
            true
        )
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    private fun scaleBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val longestSide = maxOf(source.width, source.height)
        if (longestSide <= maxDimension) return source
        val scale = maxDimension.toFloat() / longestSide.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt(),
            (source.height * scale).roundToInt(),
            true
        )
    }

    private const val DIRECTORY_NAME = "chat_images"
    private const val TAG = "ChatImageStore"
    private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    private const val MAX_FULL_DIMENSION = 1600
    private const val MAX_THUMBNAIL_DIMENSION = 360
    private const val FULL_QUALITY = 85
    private const val THUMBNAIL_QUALITY = 78
}
