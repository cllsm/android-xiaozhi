package com.xiaozhi.android.study

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.xiaozhi.android.media.StudyPreviewInfo
import com.xiaozhi.android.media.StudyObservationController
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

    fun start(context: Context) {
        if (running.get() || !starting.compareAndSet(false, true)) return

        scope.launch {
            try {
                val newController = StudyObservationController(context.applicationContext)
                val started = withContext(Dispatchers.IO) { newController.start() }
                if (!started) {
                    StudySessionState.setObservationRunning(false)
                    StudySessionState.setStatusMessage("固定机位相机启动失败，请检查相机权限或被占用")
                    withContext(Dispatchers.IO) { newController.close() }
                    return@launch
                }

                controller = newController
                running.set(true)
                StudySessionState.setObservationRunning(true)
                StudySessionState.setStatusMessage("固定机位观察已开启")

                while (isActive && running.get()) {
                    delay(POLL_INTERVAL_MS)
                    val state = StudySessionState.state.value
                    if (state.mode == StudyMode.None || !state.settings.observationEnabled) {
                        stop()
                        break
                    }

                    val speechFrame = speechRequested.getAndSet(false)
                    val interval = state.settings.observationIntervalSeconds
                        .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) * 1_000L
                    val autoDue = System.currentTimeMillis() -
                        state.lastObservationAt >= interval
                    if ((speechFrame || autoDue) && processing.compareAndSet(false, true)) {
                        try {
                            val frame = withContext(Dispatchers.IO) { captureFrame() }
                            if (frame != null) {
                                StudySessionState.recordObservationFrame(
                                    if (speechFrame) TRIGGER_SPEECH else TRIGGER_AUTO
                                )
                                when (state.mode) {
                                    StudyMode.Homework -> StudySessionManager.captureHomeworkPage(
                                        intent = HomeworkPromptBuilder.INTENT_REFRESH,
                                        image = frame
                                    )
                                    StudyMode.Reading -> StudySessionManager.captureReadingPage(
                                        image = frame
                                    )
                                    StudyMode.None -> Unit
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

    fun attachPreview(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        onResult: (StudyPreviewInfo?) -> Unit
    ) {
        controller?.attachPreview(surfaceTexture, width, height, onResult)
    }

    fun detachPreview(surface: Surface) {
        controller?.detachPreview(surface)
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
    private const val MIN_INTERVAL_SECONDS = 3
    private const val MAX_INTERVAL_SECONDS = 30
    private const val TRIGGER_AUTO = "auto"
    private const val TRIGGER_SPEECH = "speech"
}
