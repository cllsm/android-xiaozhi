package com.xiaozhi.android.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackProbeTest {
    @Test
    fun acceptsAudioAndBinaryResponses() {
        assertTrue(
            MusicPlaybackProbe.isValidPlaybackResponse(
                code = 200,
                contentType = "audio/mpeg",
                contentLength = -1L
            )
        )
        assertTrue(
            MusicPlaybackProbe.isValidPlaybackResponse(
                code = 206,
                contentType = "application/octet-stream; charset=binary",
                contentLength = 1L
            )
        )
    }

    @Test
    fun rejectsHtmlEmptyAndFailedResponses() {
        assertFalse(
            MusicPlaybackProbe.isValidPlaybackResponse(
                code = 200,
                contentType = "text/html",
                contentLength = 1024L
            )
        )
        assertFalse(
            MusicPlaybackProbe.isValidPlaybackResponse(
                code = 403,
                contentType = "audio/mpeg",
                contentLength = 1024L
            )
        )
        assertFalse(
            MusicPlaybackProbe.isValidPlaybackResponse(
                code = 200,
                contentType = "audio/mpeg",
                contentLength = 0L
            )
        )
    }
}
