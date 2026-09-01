package com.xiaozhi.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCodecsTest {
    @Test
    fun acceptsJpegHeader() {
        assertTrue(ImageCodecs.looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)))
    }

    @Test
    fun rejectsEmptyOrMalformedCameraFrame() {
        assertFalse(ImageCodecs.looksLikeJpeg(byteArrayOf()))
        assertFalse(ImageCodecs.looksLikeJpeg(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
    }
}
