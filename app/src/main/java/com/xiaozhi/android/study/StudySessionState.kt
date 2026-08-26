package com.xiaozhi.android.study

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object StudySessionState {
    private val stateFlow = MutableStateFlow(StudyRuntimeState())

    val state: StateFlow<StudyRuntimeState> = stateFlow.asStateFlow()

    fun updateSettings(settings: StudySettings) {
        updateCurrent { it.copy(settings = settings) }
    }

    fun prepare(mode: StudyMode) {
        val settings = stateFlow.value.settings
        stateFlow.value = StudyRuntimeState(
            mode = mode,
            phase = StudyPhase.Prepare,
            settings = settings,
            focusRemainingSeconds = settings.focusSeconds,
            breakRemainingSeconds = settings.breakSeconds
        )
    }

    fun activate() {
        updateCurrent { state ->
            state.copy(
                phase = StudyPhase.Active,
                startedAt = if (state.startedAt == 0L) {
                    System.currentTimeMillis()
                } else {
                    state.startedAt
                }
            )
        }
    }

    fun tickFocus() {
        updateCurrent { state ->
            if (state.focusRemainingSeconds > 0) {
                state.copy(focusRemainingSeconds = state.focusRemainingSeconds - 1)
            } else {
                state
            }
        }
    }

    fun enterBreak() {
        updateCurrent { state ->
            state.copy(
                phase = StudyPhase.Break,
                breakRemainingSeconds = state.settings.breakSeconds
            )
        }
    }

    fun tickBreak() {
        updateCurrent { state ->
            if (state.breakRemainingSeconds > 0) {
                state.copy(breakRemainingSeconds = state.breakRemainingSeconds - 1)
            } else {
                state
            }
        }
    }

    fun resumeFocus() {
        updateCurrent { state ->
            state.copy(
                phase = StudyPhase.Active,
                focusRemainingSeconds = state.settings.focusSeconds
            )
        }
    }

    fun setCaptureRunning(running: Boolean) {
        updateCurrent { it.copy(captureRunning = running) }
    }

    fun setObservationRunning(running: Boolean) {
        updateCurrent { it.copy(observationRunning = running) }
    }

    fun recordObservationFrame(trigger: String, at: Long = System.currentTimeMillis()) {
        updateCurrent { state ->
            state.copy(
                observationFrames = state.observationFrames + 1,
                lastObservationAt = at,
                lastObservationTrigger = trigger
            )
        }
    }

    fun setStatusMessage(message: String) {
        updateCurrent { it.copy(statusMessage = message) }
    }

    fun updateHomework(update: (HomeworkPageState) -> HomeworkPageState) {
        updateCurrent { state ->
            val page = state.homeworkPage ?: return@updateCurrent state
            state.copy(homeworkPage = update(page))
        }
    }

    fun updateReading(update: (ReadingPageState) -> ReadingPageState) {
        updateCurrent { state ->
            val page = state.readingPage ?: return@updateCurrent state
            state.copy(readingPage = update(page))
        }
    }

    fun reset() {
        val settings = stateFlow.value.settings
        stateFlow.value = StudyRuntimeState(settings = settings)
    }

    private fun updateCurrent(update: (StudyRuntimeState) -> StudyRuntimeState) {
        stateFlow.value = update(stateFlow.value)
    }
}
