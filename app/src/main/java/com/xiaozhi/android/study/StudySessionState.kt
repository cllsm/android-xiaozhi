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
        val now = System.currentTimeMillis()
        updateCurrent { state ->
            state.copy(
                phase = StudyPhase.Active,
                startedAt = if (state.startedAt == 0L) {
                    now
                } else {
                    state.startedAt
                },
                lastObservationAttemptAt = now
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

    fun setObservationIssue(message: String?) {
        updateCurrent { state ->
            state.copy(
                observationIssue = message.orEmpty(),
                statusMessage = message.orEmpty().ifBlank { state.statusMessage }
            )
        }
    }

    fun recordObservationFrame(trigger: String, at: Long = System.currentTimeMillis()) {
        updateCurrent { state ->
            state.copy(
                observationFrames = state.observationFrames + 1,
                lastObservationAt = at,
                lastObservationAttemptAt = at,
                lastObservationTrigger = trigger,
                lastCaptureFailure = "",
                consecutiveCaptureFailures = 0
            )
        }
    }

    fun recordObservationFailure(message: String, at: Long = System.currentTimeMillis()) {
        updateCurrent { state ->
            val failures = state.consecutiveCaptureFailures + 1
            state.copy(
                lastObservationAttemptAt = at,
                lastCaptureFailure = message,
                consecutiveCaptureFailures = failures,
                statusMessage = if (failures >= 2) {
                    "$message；语音和文字陪学仍可用，稍后自动重试"
                } else {
                    message
                }
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
