package com.xiaozhi.android.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMusicInterruptionPolicyTest {
    @Test
    fun pausesWhenServerSignalsSpeechStart() {
        assertTrue(
            VoiceMusicInterruptionPolicy.shouldPauseForUserSpeech(
                state = "start",
                text = ""
            )
        )
    }

    @Test
    fun pausesOnFirstRecognizedSpeech() {
        assertTrue(
            VoiceMusicInterruptionPolicy.shouldPauseForUserSpeech(
                state = "interim",
                text = "继续"
            )
        )
    }

    @Test
    fun keepsMusicPlayingForBlankStopEvent() {
        assertFalse(
            VoiceMusicInterruptionPolicy.shouldPauseForUserSpeech(
                state = "stop",
                text = ""
            )
        )
    }
}
