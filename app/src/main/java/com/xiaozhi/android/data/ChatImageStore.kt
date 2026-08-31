package com.xiaozhi.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        val bitmap = decodeOrientedBitmap(sourceBytes) ?: return@withContext null
        val fullBitmap = scaleBitmap(bitmap, MAX_FULL_DIMENSION)
        val thumbnailBitmap = scaleBitmap(bitmap, MAX_THUMBNAIL_DIMENSION)
        val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val fullFile = File(directory, "$id.jpg")
        val thumbnailFile = File(directory, "${id}_thumb.jpg")

        val fullWritten = fullFile.outputStream().use { output ->
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, FULL_QUALITY, output)
        }
        val thumbnailWritten = thumbnailFile.outputStream().use { output ->
            thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
        }
        if (fullBitmap != bitmap) fullBitmap.recycle()
        if (thumbnailBitmap != bitmap) thumbnailBitmap.recycle()
        bitmap.recycle()

        if (!fullWritten || !thumbnailWritten) {
            fullFile.delete()
            thumbnailFile.delete()
            null
        } else {
            StoredChatImage(
                uploadBytes = fullFile.readBytes(),
                fullPath = fullFile.absolutePath,
                thumbnailPath = thumbnailFile.absolutePath
            )
        }
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
    private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    private const val MAX_FULL_DIMENSION = 1600
    private const val MAX_THUMBNAIL_DIMENSION = 360
    private const val FULL_QUALITY = 85
    private const val THUMBNAIL_QUALITY = 78
}
