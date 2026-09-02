package com.xiaozhi.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Looper
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.util.Size
import com.xiaozhi.android.core.YuvFrameCodec
import com.xiaozhi.android.study.StudyCameraFacing
import com.xiaozhi.android.study.StudySessionState
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class StudyPreviewInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val lensFacing: Int,
    val isFrontCamera: Boolean,
    val mirror: Boolean
)

/**
 * Keeps one camera session alive while study mode is active. Frames are still
 * captured on demand, so no video is recorded and no buffer is kept after JPEG
 * conversion.
 */
class StudyObservationController(
    private val context: Context,
    private val preferredFacing: StudyCameraFacing = StudyCameraFacing.Back
) {
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var characteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSize: Size? = null
    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    val isReady: Boolean get() = ready.get() && !closed.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (closed.get()) return false
        if (ready.get()) return true
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantedFacing = when (preferredFacing) {
            StudyCameraFacing.Back -> CameraCharacteristics.LENS_FACING_BACK
            StudyCameraFacing.Front -> CameraCharacteristics.LENS_FACING_FRONT
        }
        val selectedCameraId = selectCamera(manager, wantedFacing) ?: return false
        val selectedCharacteristics = manager.getCameraCharacteristics(selectedCameraId)
        characteristics = selectedCharacteristics
        lensFacing = selectedCharacteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        sensorOrientation = selectedCharacteristics.get(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 0
        val size = selectSize(selectedCharacteristics)

        return try {
            val thread = HandlerThread("xiaozhi-study-observation").also { it.start() }
            val cameraHandler = Handler(thread.looper)
            val imageReader = ImageReader.newInstance(
                size.first,
                size.second,
                android.graphics.ImageFormat.YUV_420_888,
                MAX_IMAGES
            )
            val opened = CountDownLatch(1)
            manager.openCamera(
                selectedCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        opened.countDown()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        closeDevice(device)
                        opened.countDown()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        closeDevice(device)
                        opened.countDown()
                    }
                },
                cameraHandler
            )
            if (!opened.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                closeInternal()
                return false
            }

            val activeCamera = camera ?: run {
                closeInternal()
                return false
            }
            val configured = CountDownLatch(1)
            activeCamera.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(newSession: CameraCaptureSession) {
                        session = newSession
                        configured.countDown()
                    }

                    override fun onConfigureFailed(newSession: CameraCaptureSession) {
                        newSession.close()
                        session = null
                        configured.countDown()
                    }
                },
                cameraHandler
            )
            if (!configured.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) ||
                session == null
            ) {
                closeInternal()
                return false
            }

            synchronized(stateLock) {
                handlerThread = thread
                handler = cameraHandler
                reader = imageReader
            }
            ready.set(true)
            true
        } catch (_: Exception) {
            closeInternal()
            false
        }
    }

    fun attachPreview(
        surfaceTexture: SurfaceTexture,
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        onResult: (StudyPreviewInfo?) -> Unit
    ) {
        if (!isReady || viewWidth <= 0 || viewHeight <= 0) {
            onResultOnUiThread(onResult, null)
            return
        }
        val cameraHandler = handler ?: run {
            onResultOnUiThread(onResult, null)
            return
        }

        cameraHandler.post {
            if (closed.get() || !ready.get()) {
                onResultOnUiThread(onResult, null)
                return@post
            }

            val size = selectPreviewSize(
                viewWidth,
                viewHeight,
                sensorOrientation,
                displayRotation * 90
            )
            runCatching {
                surfaceTexture.setDefaultBufferSize(size.width, size.height)
            }
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            previewSurfaceTexture = surfaceTexture
            val activeCamera = camera
            val readerSurface = reader?.surface
            if (activeCamera == null || readerSurface == null) {
                onResultOnUiThread(onResult, null)
                return@post
            }

            runCatching {
                activeCamera.createCaptureSession(
                    listOf(readerSurface, surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(newSession: CameraCaptureSession) {
                            if (closed.get()) {
                                newSession.close()
                                onResultOnUiThread(onResult, null)
                                return
                            }
                            session = newSession
                            val request = activeCamera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply { addTarget(surface) }.build()
                            val repeatingResult = runCatching {
                                newSession.setRepeatingRequest(request, null, cameraHandler)
                            }.isSuccess
                            if (repeatingResult) {
                                previewSize = size
                                val previewRotation = StudyPreviewOrientation.previewRotationDegrees(
                                    sensorOrientationDegrees = sensorOrientation,
                                    isFrontCamera = lensFacing ==
                                        CameraCharacteristics.LENS_FACING_FRONT,
                                    displayRotation = displayRotation
                                )
                                Log.i(
                                    "StudyPreview",
                                    "attached:size=${size.width}x${size.height}," +
                                        "sensor=$sensorOrientation,display=$displayRotation," +
                                        "facing=$lensFacing,rotation=$previewRotation"
                                )
                                onResultOnUiThread(
                                    onResult,
                                    StudyPreviewInfo(
                                        width = size.width,
                                        height = size.height,
                                        rotationDegrees = sensorOrientation,
                                        lensFacing = lensFacing,
                                        isFrontCamera = lensFacing ==
                                            CameraCharacteristics.LENS_FACING_FRONT,
                                        mirror = lensFacing ==
                                            CameraCharacteristics.LENS_FACING_FRONT
                                    )
                                )
                            } else {
                                previewSurface = null
                                onResultOnUiThread(onResult, null)
                            }
                        }

                        override fun onConfigureFailed(newSession: CameraCaptureSession) {
                            newSession.close()
                            if (session === newSession) session = null
                            previewSurface = null
                            onResultOnUiThread(onResult, null)
                        }
                    },
                    cameraHandler
                )
            }.onFailure {
                previewSurface = null
                onResultOnUiThread(onResult, null)
            }
        }
    }

    fun detachPreview(surfaceTexture: SurfaceTexture) {
        val cameraHandler = handler ?: return
        cameraHandler.post {
            val previewTexture = previewSurfaceTexture
            if (previewTexture !== surfaceTexture) return@post
            previewSurface = null
            previewSize = null
            previewSurfaceTexture = null
            if (closed.get() || !ready.get()) return@post

            runCatching { session?.stopRepeating() }
            val activeCamera = camera
            val readerSurface = reader?.surface
            if (activeCamera == null || readerSurface == null) return@post
            runCatching {
                activeCamera.createCaptureSession(
                    listOf(readerSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(newSession: CameraCaptureSession) {
                            if (!closed.get()) session = newSession
                            else newSession.close()
                        }

                        override fun onConfigureFailed(newSession: CameraCaptureSession) {
                            newSession.close()
                        }
                    },
                    cameraHandler
                )
            }
        }
    }

    fun capture(): ByteArray? {
        if (!isReady) return null
        val activeSession = session ?: return null
        val activeCamera = camera ?: return null
        val imageReader = reader ?: return null

        return try {
            // Drain images left by an earlier capture, then wait for this
            // capture's JPEG to actually reach the reader. onCaptureCompleted
            // alone does not guarantee that the buffer is available yet.
            imageReader.setOnImageAvailableListener(null, handler)
            while (true) {
                val staleImage = imageReader.acquireLatestImage() ?: break
                staleImage.close()
            }
            val frameAvailable = CountDownLatch(1)
            val captureFailed = AtomicBoolean(false)
            imageReader.setOnImageAvailableListener(
                { frameAvailable.countDown() },
                handler
            )
            val request = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                .apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                }
                .build()
            val captured = CountDownLatch(1)
            activeSession.capture(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        captured.countDown()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        captureFailed.set(true)
                        captured.countDown()
                    }
                },
                handler
            )

            val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
            while (!frameAvailable.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                if (captureFailed.get()) return null
                if (SystemClock.elapsedRealtime() >= deadline) return null
            }
            if (!captured.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            val image = imageReader.acquireLatestImage() ?: return null
            image.use { capturedImage ->
                val nv21 = YuvFrameCodec.imageToNv21(capturedImage)
                YuvFrameCodec.compressToJpeg(
                    nv21,
                    capturedImage.width,
                    capturedImage.height,
                    JPEG_QUALITY
                )?.let { jpeg ->
                    // 传感器方向叠加用户手动校正，保持识别图与预览方向一致
                    val manualOffset = StudySessionState.state.value.previewRotationOffset
                    rotateIfNeeded(jpeg, sensorOrientation + manualOffset)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        closed.set(true)
        ready.set(false)
        closeInternal()
    }

    private fun closeInternal() {
        ready.set(false)
        synchronized(stateLock) {
            runCatching { session?.close() }
            session = null
            camera?.let { closeDevice(it) }
            camera = null
            runCatching { reader?.close() }
            reader = null
            handler = null
            handlerThread?.quitSafely()
            handlerThread = null
        }
    }

    private fun closeDevice(device: CameraDevice) {
        runCatching { device.close() }
        if (camera === device) camera = null
    }

    private fun selectCamera(manager: CameraManager, wantedFacing: Int): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == wantedFacing
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun selectSize(characteristics: CameraCharacteristics): Pair<Int, Int> {
        val sizes = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888).orEmpty()
        val target = sizes.firstOrNull { size ->
            maxOf(size.width, size.height) <= MAX_DIMENSION &&
                maxOf(size.width, size.height) >= PREFERRED_DIMENSION
        } ?: sizes.firstOrNull()
        return target?.let { Pair(it.width, it.height) } ?: Pair(1280, 720)
    }

    private fun selectPreviewSize(
        viewWidth: Int,
        viewHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int
    ): Size {
        val activeCharacteristics = characteristics
        val sizes = activeCharacteristics?.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(Surface::class.java).orEmpty()
        // Sensor buffers are rotated before display. Match the aspect after that
        // rotation so center-crop does not throw away a disproportionate frame.
        val previewRotation = StudyPreviewOrientation.previewRotationDegrees(
            sensorOrientationDegrees = sensorOrientationDegrees,
            isFrontCamera = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            displayRotation = displayRotationDegrees / 90
        )
        val targetAspect = if (StudyPreviewOrientation.isQuarterTurn(previewRotation)) {
            viewHeight.toDouble() / viewWidth.toDouble()
        } else {
            viewWidth.toDouble() / viewHeight.toDouble()
        }
        return sizes
            .filter { maxOf(it.width, it.height) <= MAX_PREVIEW_DIMENSION }
            .minByOrNull { size ->
                val aspect = size.width.toDouble() / size.height.toDouble()
                val aspectDistance = kotlin.math.abs(aspect - targetAspect)
                val resolutionDistance = kotlin.math.abs(
                    maxOf(size.width, size.height) - PREFERRED_PREVIEW_DIMENSION
                ).toDouble() / PREFERRED_PREVIEW_DIMENSION
                aspectDistance * 2.0 + resolutionDistance
            }
            ?: sizes.firstOrNull()
            ?: Size(1280, 720)
    }

    private fun <T> onResultOnUiThread(callback: (T?) -> Unit, value: T?) {
        mainHandler.post { callback(value) }
    }

    private fun rotateIfNeeded(bytes: ByteArray, orientationDegrees: Int): ByteArray {
        // 归一化角度（如 270 + 90 = 360 应视为 0）
        val normalized = ((orientationDegrees % 360) + 360) % 360
        if (normalized == 0) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (rotated != bitmap) rotated.recycle()
        bitmap.recycle()
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "StudyObservation"
        const val JPEG_QUALITY = 85
        const val MAX_IMAGES = 3
        const val MAX_DIMENSION = 1920
        const val PREFERRED_DIMENSION = 1280
        const val MAX_PREVIEW_DIMENSION = 1920
        const val PREFERRED_PREVIEW_DIMENSION = 1280
        const val OPEN_TIMEOUT_SECONDS = 5L
        const val SESSION_TIMEOUT_SECONDS = 5L
        const val CAPTURE_TIMEOUT_MS = 4_000L
        const val FRAME_WAIT_MS = 40L
    }
}
