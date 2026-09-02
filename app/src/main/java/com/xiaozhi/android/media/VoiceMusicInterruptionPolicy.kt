package com.xiaozhi.android.media

object VoiceMusicInterruptionPolicy {
    fun shouldPauseForUserSpeech(state: String, text: String): Boolean {
        return state == "start" || text.isNotBlank()
    }
}
