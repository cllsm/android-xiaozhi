package com.xiaozhi.android.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicRuntimeState(
    val loading: Boolean = false,
    val hasTrack: Boolean = false,
    val paused: Boolean = false,
    val title: String = "",
    val sourceName: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false
) {
    val active: Boolean get() = loading || hasTrack
    val playbackStatusLabel: String
        get() = when {
            loading -> "匹配中"
            hasTrack && paused -> "已暂停"
            hasTrack -> "正在播放"
            else -> ""
        }
}

object MusicPlaybackState {
    private val _state = MutableStateFlow(MusicRuntimeState())

    val state: StateFlow<MusicRuntimeState> = _state.asStateFlow()

    fun update(transform: (MusicRuntimeState) -> MusicRuntimeState) {
        _state.value = transform(_state.value)
    }

    fun clear() {
        _state.value = MusicRuntimeState()
    }
}
