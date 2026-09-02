package com.xiaozhi.android.core

object ImageCodecs {
    fun looksLikeJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= MIN_JPEG_BYTES &&
            bytes[0] == JPEG_FIRST_BYTE &&
            bytes[1] == JPEG_SECOND_BYTE
    }

    private const val MIN_JPEG_BYTES = 4
    private const val JPEG_FIRST_BYTE: Byte = 0xFF.toByte()
    private const val JPEG_SECOND_BYTE: Byte = 0xD8.toByte()
}
