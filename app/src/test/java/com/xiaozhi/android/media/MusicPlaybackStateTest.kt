package com.xiaozhi.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackStateTest {
    @Test
    fun updatesPlaybackStateAtomically() {
        MusicPlaybackState.clear()

        MusicPlaybackState.update { it.copy(loading = true) }
        MusicPlaybackState.update {
            MusicRuntimeState(
                loading = true,
                title = "晴天",
                sourceName = "网易云无损"
            )
        }
        MusicPlaybackState.update { it.copy(hasTrack = true, loading = false) }
        MusicPlaybackState.update { it.copy(paused = true) }

        val state = MusicPlaybackState.state.value
        assertTrue(state.active)
        assertTrue(state.paused)
        assertEquals("已暂停", state.playbackStatusLabel)
        assertEquals("晴天", state.title)
        assertEquals("网易云无损", state.sourceName)

        MusicPlaybackState.update { it.copy(paused = false) }
        assertEquals("正在播放", MusicPlaybackState.state.value.playbackStatusLabel)

        MusicPlaybackState.update { it.copy(loading = true, hasTrack = false, paused = false) }
        assertEquals("匹配中", MusicPlaybackState.state.value.playbackStatusLabel)

        MusicPlaybackState.clear()
        assertFalse(MusicPlaybackState.state.value.active)
        assertEquals("", MusicPlaybackState.state.value.playbackStatusLabel)
    }
}
