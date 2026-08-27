package com.xiaozhi.android.study

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.xiaozhi.android.media.StudyPreviewInfo
import com.xiaozhi.android.media.StudyObservationController
import com.xiaozhi.android.study.StudyCameraFacing
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StudyObservationEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val starting = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val frameCaptureBusy = AtomicBoolean(false)
    private val speechRequested = AtomicBoolean(false)
    private var controller: StudyObservationController? = null

    val isRunning: Boolean get() = running.get()

    fun start(context: Context, preferredFacing: StudyCameraFacing = StudyCameraFacing.Back) {
        if (running.get() || !starting.compareAndSet(false, true)) return

        scope.launch {
            try {
                val newController = StudyObservationController(
                    context.applicationContext,
                    preferredFacing
                )
                val started = withContext(Dispatchers.IO) { newController.start() }
                if (!started) {
                    StudySessionState.setObservationRunning(false)
                    StudySessionState.setObservationIssue("相机暂时不可用，语音和文字陪学仍可用")
                    withContext(Dispatchers.IO) { newController.close() }
                    return@launch
                }

                controller = newController
                running.set(true)
                StudySessionState.setObservationRunning(true)
                StudySessionState.setObservationIssue(null)
                StudySessionState.setStatusMessage("固定机位观察已开启")

                while (isActive && running.get()) {
                    delay(POLL_INTERVAL_MS)
                    val state = StudySessionState.state.value
                    if (state.mode == StudyMode.None || !state.settings.observationEnabled) {
                        stop()
                        break
                    }

                    val requestedSpeechFrame = speechRequested.get()
                    val interval = state.settings.observationIntervalSeconds
                        .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) * 1_000L
                    val autoDue = System.currentTimeMillis() -
                        state.lastObservationAttemptAt >= interval
                    val observationDue = state.phase == StudyPhase.Active &&
                        (requestedSpeechFrame || autoDue)
                    if (observationDue && processing.compareAndSet(false, true)) {
                        try {
                            val speechFrame = speechRequested.getAndSet(false)
                            val frame = withContext(Dispatchers.IO) { captureFrame() }
                            if (frame == null) {
                                StudySessionState.recordObservationFailure(
                                    "相机取帧失败，已保留本次陪学"
                                )
                            } else {
                                StudySessionState.recordObservationFrame(
                                    if (speechFrame) TRIGGER_SPEECH else TRIGGER_AUTO
                                )
                                val result = when (state.mode) {
                                    StudyMode.Homework -> StudySessionManager.captureHomeworkPage(
                                        intent = HomeworkPromptBuilder.INTENT_REFRESH,
                                        image = frame
                                    )
                                    StudyMode.Reading -> StudySessionManager.captureReadingPage(
                                        image = frame
                                    )
                                    StudyMode.None -> null
                                }
                                if (result?.success == false) {
                                    StudySessionState.recordObservationFailure(result.message)
                                }
                            }
                        } finally {
                            processing.set(false)
                        }
                    }
                }
            } finally {
                starting.set(false)
            }
        }
    }

    fun requestSpeechFrame() {
        if (running.get()) speechRequested.set(true)
    }

    fun switchCamera(context: Context, preferredFacing: StudyCameraFacing) {
        if (!running.get()) {
            start(context, preferredFacing)
            return
        }

        running.set(false)
        speechRequested.set(false)
        StudySessionState.setObservationRunning(false)
        StudySessionState.setStatusMessage("正在切换摄像头...")
        val activeController = controller
        controller = null
        scope.launch {
            withContext(Dispatchers.IO) { activeController?.close() }
            // The active observation coroutine may still be unwinding. Wait for
            // its startup guard before attempting the replacement camera.
            var attempts = 0
            while (starting.get() && attempts < SWITCH_START_TIMEOUT_MS / SWITCH_POLL_MS) {
                delay(SWITCH_POLL_MS)
                attempts++
            }
            start(context, preferredFacing)
        }
    }

    fun attachPreview(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        displayRotation: Int,
        onResult: (StudyPreviewInfo?) -> Unit
    ) {
        controller?.attachPreview(surfaceTexture, width, height, displayRotation, onResult)
    }

    fun detachPreview(surfaceTexture: SurfaceTexture) {
        controller?.detachPreview(surfaceTexture)
    }

    fun captureFrame(): ByteArray? {
        if (!running.get() || !frameCaptureBusy.compareAndSet(false, true)) return null
        val activeController = controller ?: run {
            frameCaptureBusy.set(false)
            return null
        }
        return try {
            activeController.capture()
        } finally {
            frameCaptureBusy.set(false)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        speechRequested.set(false)
        StudySessionState.setObservationRunning(false)
        val activeController = controller
        controller = null
        scope.launch(Dispatchers.IO) {
            activeController?.close()
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private const val POLL_INTERVAL_MS = 250L
    private const val SWITCH_POLL_MS = 25L
    private const val SWITCH_START_TIMEOUT_MS = 2_000L
    private const val MIN_INTERVAL_SECONDS = 3
    private const val MAX_INTERVAL_SECONDS = 30
    private const val TRIGGER_AUTO = "auto"
    private const val TRIGGER_SPEECH = "speech"
}
