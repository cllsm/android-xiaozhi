package com.xiaozhi.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.xiaozhi.android.core.ImageCodecs
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraCaptureController(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun capture(): ByteArray? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCamera(manager)
            ?: run { return null }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val size = selectSize(characteristics)
        var reader: ImageReader? = null
        var thread: HandlerThread? = null
        var camera: CameraDevice? = null

        return try {
            thread = HandlerThread("xiaozhi-camera").also { it.start() }
            val handler = Handler(thread.looper)
            reader = ImageReader.newInstance(size.first, size.second, android.graphics.ImageFormat.JPEG, 2)
            val opened = CountDownLatch(1)
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        opened.countDown()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        opened.countDown()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        opened.countDown()
                    }
                },
                handler
            )
            if (!opened.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null

            val activeCamera = camera ?: return null
            val surface: Surface = reader.surface
            val configured = CountDownLatch(1)
            var session: CameraCaptureSession? = null
            activeCamera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(newSession: CameraCaptureSession) {
                        session = newSession
                        configured.countDown()
                    }

                    override fun onConfigureFailed(newSession: CameraCaptureSession) {
                        configured.countDown()
                    }
                },
                handler
            )
            if (!configured.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null

            val activeSession = session ?: return null
            val requestBuilder: CaptureRequest.Builder = activeCamera
                .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(surface)
            requestBuilder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
            val request = requestBuilder.build()
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
                },
                handler
            )

            val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
            while (!captured.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                if (SystemClock.elapsedRealtime() >= deadline) return null
            }
            val image = reader.acquireLatestImage() ?: return null
            image.use { capturedImage ->
                val buffer = capturedImage.planes.first().buffer
                buffer.rewind()
                val bytes = ByteArray(buffer.remaining())
                for (index in bytes.indices) {
                    bytes[index] = buffer.get()
                }
                if (!ImageCodecs.looksLikeJpeg(bytes)) {
                    Log.w(TAG, "Camera returned invalid JPEG, bytes=${bytes.size}")
                    null
                } else {
                    rotateIfNeeded(bytes, sensorOrientation)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                camera?.close()
            } catch (_: Exception) {
            }
            reader?.close()
            thread?.quitSafely()
        }
    }

    private fun selectCamera(manager: CameraManager): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun selectSize(characteristics: CameraCharacteristics): Pair<Int, Int> {
        val sizes = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(android.graphics.ImageFormat.JPEG).orEmpty()
        val target = sizes.firstOrNull { size ->
            maxOf(size.width, size.height) <= MAX_DIMENSION &&
                maxOf(size.width, size.height) >= PREFERRED_DIMENSION
        } ?: sizes.firstOrNull()
        return target?.let { Pair(it.width, it.height) } ?: Pair(1280, 720)
    }

    private fun rotateIfNeeded(bytes: ByteArray, orientationDegrees: Int): ByteArray {
        if (orientationDegrees == 0) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
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
        private const val TAG = "CameraCapture"
        private const val JPEG_QUALITY = 85
        private const val MAX_DIMENSION = 1920
        private const val PREFERRED_DIMENSION = 1280
        private const val OPEN_TIMEOUT_SECONDS = 5L
        private const val SESSION_TIMEOUT_SECONDS = 5L
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val FRAME_WAIT_MS = 40L
    }
}
